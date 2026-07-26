(ns kotoba.tamaki.visual
  "Screenshot and deterministic visual feedback for the Observatory loop."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kotoba.tamaki.delivery :as delivery])
  (:import [java.util.concurrent TimeUnit]))

(def window-query
  (str "import CoreGraphics; import Foundation; "
       "let xs = CGWindowListCopyWindowInfo([.optionAll,"
       ".excludeDesktopElements], kCGNullWindowID)! as! [[String:Any]]; "
       "for x in xs { let n=x[kCGWindowName as String] as? String ?? \"\"; "
       "if n.contains(\"Tamaki Observatory\") { "
       "print(x[kCGWindowNumber as String]!); break } }"))

(def ocr-script
  (str "import Foundation; import Vision; "
       "let u=URL(fileURLWithPath:CommandLine.arguments[1]); "
       "let r=VNRecognizeTextRequest(); r.recognitionLevel = .accurate; "
       "try VNImageRequestHandler(url:u).perform([r]); "
       "for x in (r.results ?? []) { "
       "if let c=try? x.topCandidates(1).first { print(c.string) } }"))

(def metrics-script
  (str "import Foundation; import CoreGraphics; import ImageIO; "
       "let u=URL(fileURLWithPath:CommandLine.arguments[1]) as CFURL; "
       "guard let s=CGImageSourceCreateWithURL(u,nil), "
       "let im=CGImageSourceCreateImageAtIndex(s,0,nil) else { exit(2) }; "
       "let w=im.width, h=im.height, row=w*4; "
       "var p=[UInt8](repeating:0,count:row*h); "
       "let cs=CGColorSpaceCreateDeviceRGB(); "
       "guard let c=CGContext(data:&p,width:w,height:h,bitsPerComponent:8,"
       "bytesPerRow:row,space:cs,"
       "bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue) else { exit(3) }; "
       "c.draw(im,in:CGRect(x:0,y:0,width:w,height:h)); "
       "let x0=Int(Double(w)*0.08), x1=Int(Double(w)*0.72); "
       "let y0=Int(Double(h)*0.23), y1=Int(Double(h)*0.88); "
       "var n=0, sum=0.0, sum2=0.0, colored=0; "
       "for y in stride(from:y0,to:y1,by:8) { "
       "for x in stride(from:x0,to:x1,by:8) { let i=y*row+x*4; "
       "let r=Double(p[i]), g=Double(p[i+1]), b=Double(p[i+2]); "
       "let l=(299*r+587*g+114*b)/1000.0; "
       "sum += l; sum2 += l*l; n += 1; "
       "if max(r,g,b)-min(r,g,b) > 18 { colored += 1 } } }; "
       "let mean=sum/Double(max(1,n)); "
       "let variance=max(0,sum2/Double(max(1,n))-mean*mean); "
       "print(\"\\(w) \\(h) \\(mean) \\(sqrt(variance)) "
       "\\(Double(colored)/Double(max(1,n)))\")"))

(defn- execute-with-timeout [argv cwd timeout-seconds]
  (let [process (.start (doto (ProcessBuilder. ^java.util.List argv)
                          (.directory (io/file cwd))))
        out (future (slurp (.getInputStream process)))
        err (future (slurp (.getErrorStream process)))
        read-future (fn [value]
                      (try @value (catch Exception _ "")))]
    (if (.waitFor process timeout-seconds TimeUnit/SECONDS)
      {:exit (.exitValue process)
       :out (read-future out) :err (read-future err)}
      (do
        (.destroyForcibly process)
        (.waitFor process)
        (future-cancel out)
        (future-cancel err)
        {:exit 124 :out "" :err "visual analysis timed out"}))))

(defn- prune! [dir]
  (doseq [file (drop 20 (sort-by #(.lastModified ^java.io.File %)
                                 >
                                 (or (seq (.listFiles dir)) [])))]
    (when (and (.isFile file)
               (re-matches #"observatory-\d+\.png" (.getName file)))
      (.delete file))))

(defn capture! [state-root now-ms]
  (let [dir (io/file state-root "visual")
        _ (.mkdirs dir)
        window (delivery/execute! ["swift" "-e" window-query])
        window-id (some-> (:out window) str/trim not-empty)
        image (io/file dir (str "observatory-" now-ms ".png"))]
    (if-not (and (zero? (:exit window)) window-id)
      {:visual/status :unavailable
       :visual/error "Tamaki Observatory window was not found"}
      (let [captured (delivery/execute!
                      ["screencapture" "-x" "-l" window-id
                       (.getAbsolutePath image)])]
        (if (and (zero? (:exit captured)) (.isFile image)
                 (pos? (.length image)))
          (do
            (delivery/execute! ["sips" "-Z" "1280" (.getAbsolutePath image)])
            (prune! dir)
            {:visual/status :captured
             :visual/path (.getAbsolutePath image)
             :visual/bytes (.length image)
             :visual/window-id window-id})
          {:visual/status :unavailable
           :visual/error (str/trim (str (:err captured)))})))))

(defn- canvas-metrics [project path]
  (let [result (execute-with-timeout
                ["swift" "-e" metrics-script path] project 20)]
    (when-not (zero? (:exit result))
      (throw (ex-info "CoreGraphics screenshot analysis failed"
                      {:exit (:exit result) :error (:err result)})))
    (let [[width height mean stddev color-ratio]
          (str/split (str/trim (:out result)) #"\s+")]
      (when-not color-ratio
        (throw (ex-info "Unexpected screenshot metrics output"
                        {:output (:out result)})))
      {:canvas/luma (Double/parseDouble mean)
       :canvas/stddev (Double/parseDouble stddev)
       :canvas/color-ratio (Double/parseDouble color-ratio)
       :image/width (Long/parseLong width)
       :image/height (Long/parseLong height)})))

(defn analyze! [project capture]
  (if-not (= :captured (:visual/status capture))
    capture
    (try
      (let [metrics (canvas-metrics project (:visual/path capture))
            ocr (execute-with-timeout
                 ["swift" "-e" ocr-script (:visual/path capture)]
                 project 20)
            text (str/lower-case (:out ocr))
            findings (cond-> []
                       (< (:canvas/stddev metrics) 8.0)
                       (conj "3D canvas region appears blank or lacks grid contrast")
                       (not (str/includes? text "tamaki observatory"))
                       (conj "Observatory title was not recognized")
                       (not-every? #(str/includes? text %)
                                   ["codex" "claude" "grok"])
                       (conj "One or more provider usage cards are not visible")
                       (not (str/includes? text "activity"))
                       (conj "Live activity panel was not recognized"))
            degraded? (seq findings)]
        (merge capture metrics
               {:visual/status (if degraded? :degraded :healthy)
                :visual/findings findings
                :visual/suggested-issue
                (if degraded?
                  (str "Restore Observatory visual health: "
                       (str/join "; " findings))
                  "")}))
      (catch Exception error
        (assoc capture :visual/status :analysis-failed
               :visual/error (.getMessage error))))))

(defn observe! [project state-root now-ms]
  (analyze! project (capture! state-root now-ms)))
