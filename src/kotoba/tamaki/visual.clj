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

(defn execute-with-timeout
  "Run argv in cwd with a bounded wall-clock timeout. Public (not defn-) so
  tests can inject deterministic swift/OCR/CoreGraphics stand-ins the same
  way kotoba.tamaki.delivery/execute! is stubbed for capture! tests."
  [argv cwd timeout-seconds]
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

(def min-webkit-width
  "Minimum pixel width for a WebKit live.png to be preferred over screen-capture.
  Observed 767px snapshots omit Observatory provider usage cards; frames at
  >=1100px keep them legible to Vision OCR."
  1000)

(def min-webkit-height
  "Minimum pixel height for a WebKit live.png to be preferred over screen-capture."
  700)

(defn png-dimensions
  "Read width/height from a PNG file's IHDR chunk without decoding pixels.
  Returns {:width w :height h} or nil when the file is not a readable PNG."
  [file]
  (try
    (with-open [in (java.io.DataInputStream.
                    (java.io.BufferedInputStream. (io/input-stream file)))]
      (let [sig (byte-array 8)]
        (.readFully in sig)
        (when (= (mapv #(bit-and % 0xff) sig)
                 [0x89 0x50 0x4e 0x47 0x0d 0x0a 0x1a 0x0a])
          (let [length (.readInt in)
                ctype (byte-array 4)]
            (.readFully in ctype)
            (when (and (= length 13)
                       (= (String. ctype java.nio.charset.StandardCharsets/US_ASCII)
                          "IHDR"))
              {:width (.readInt in)
               :height (.readInt in)})))))
    (catch Exception _ nil)))

(defn usable-webkit-snapshot?
  "True when a WebKit live.png is large enough that Observatory provider usage
  cards remain OCR-legible. Undersized snapshots (observed around 767x548) omit
  those cards and produce false :degraded visual health verdicts."
  ([file]
   (usable-webkit-snapshot? file min-webkit-width min-webkit-height))
  ([file min-w min-h]
   (boolean
    (when-let [{:keys [width height]} (png-dimensions file)]
      (and (pos? min-w) (pos? min-h)
           (>= width min-w)
           (>= height min-h))))))

(defn capture! [state-root now-ms]
  (let [dir (io/file state-root "visual")
        _ (.mkdirs dir)
        live (io/file dir "live.png")
        image (io/file dir (str "observatory-" now-ms ".png"))]
    (if (and (.isFile live) (pos? (.length live))
             (< (- now-ms (.lastModified live)) 30000)
             (usable-webkit-snapshot? live))
      (do
        (io/copy live image)
        (prune! dir)
        {:visual/status :captured
         :visual/path (.getAbsolutePath image)
         :visual/bytes (.length image)
         :visual/source :webkit})
      (let [window (delivery/execute! ["swift" "-e" window-query])
            window-id (some-> (:out window) str/trim not-empty)]
        (if-not (and (zero? (:exit window)) window-id)
          {:visual/status :unavailable
           :visual/error
           (str "No fresh WebKit snapshot and Observatory window was not found"
                (when-let [error (some-> (:err window) str/trim not-empty)]
                  (str ": " error)))}
          (let [captured (delivery/execute!
                          ["screencapture" "-x" "-l" window-id
                           (.getAbsolutePath image)])]
            (if (and (zero? (:exit captured)) (.isFile image)
                     (pos? (.length image)))
              (do
                (delivery/execute! ["sips" "-Z" "1280"
                                    (.getAbsolutePath image)])
                (prune! dir)
                {:visual/status :captured
                 :visual/path (.getAbsolutePath image)
                 :visual/bytes (.length image)
                 :visual/source :screen-capture
                 :visual/window-id window-id})
              {:visual/status :unavailable
               :visual/error (str/trim (str (:err captured)))})))))))

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

(defn- usage-stat-count
  "Count inbound usage counters typical of provider usage cards ('in 12345')."
  [text]
  (count (re-seq #"\bin\s+\d{2,}" text)))

(defn provider-usage-cards-visible?
  "True when OCR evidence shows the Observatory provider usage cards.

  Prefer exact labels (codex + claude + grok). When Vision misreads a short
  colored label — observed on purple 'grok' cards as 'CrOR' — accept a
  structural fallback: several inbound usage-stat blocks plus at least two of
  the named providers. Live-activity chatter alone is not enough because it
  rarely emits the 'in <digits>' card pattern."
  [text]
  (let [has-codex (str/includes? text "codex")
        has-claude (str/includes? text "claude")
        has-grok (str/includes? text "grok")
        named (count (filter identity [has-codex has-claude has-grok]))
        stats (usage-stat-count text)]
    (or (and has-codex has-claude has-grok)
        (and (>= stats 3) (>= named 2)))))

(defn- undersized-capture?
  "True when canvas metrics report an image smaller than the minimum size at
  which Observatory provider usage cards remain legible (see
  min-webkit-width/min-webkit-height). A capture!'d screen-capture image never
  passes through the webkit freshness/size gate, so a shrunk Observatory
  window can still reach OCR and produce the same ambiguous 'cards are not
  visible' finding as a real detection regression. Missing :image/width or
  :image/height (e.g. callers exercising findings unrelated to image size) is
  treated as size-unknown, not undersized, so existing behaviour is unchanged."
  [metrics]
  (let [width (:image/width metrics)
        height (:image/height metrics)]
    (boolean (and width height
                  (or (< width min-webkit-width)
                      (< height min-webkit-height))))))

(defn evaluate-observatory
  "Pure decision logic for Observatory visual health: given CoreGraphics canvas
  metrics and lower-cased OCR text, return the verdict map (:visual/status,
  :visual/findings, :visual/suggested-issue). This is the highest
  decision-density part of the visual feedback loop (it decides :healthy vs
  :degraded and drives issue prioritization), so it is kept free of process
  execution and IO to make it deterministically testable without mocking
  screenshots, OCR, or subprocesses."
  [metrics text]
  (let [findings (cond-> []
                   (< (:canvas/stddev metrics) 8.0)
                   (conj "3D canvas region appears blank or lacks grid contrast")
                   (not (str/includes? text "tamaki observatory"))
                   (conj "Observatory title was not recognized")
                   (not (provider-usage-cards-visible? text))
                   (conj "One or more provider usage cards are not visible")
                   (and (not (provider-usage-cards-visible? text))
                        (undersized-capture? metrics))
                   (conj (str "Captured image is " (:image/width metrics) "x"
                              (:image/height metrics) ", below the "
                              min-webkit-width "x" min-webkit-height
                              " minimum needed to read provider usage cards"
                              " reliably; resize the Observatory window before"
                              " treating this as a functional regression"))
                   (not (str/includes? text "activity"))
                   (conj "Live activity panel was not recognized"))
        degraded? (seq findings)]
    {:visual/status (if degraded? :degraded :healthy)
     :visual/findings findings
     :visual/suggested-issue
     (if degraded?
       (str "Restore Observatory visual health: "
            (str/join "; " findings))
       "")}))

(def max-ocr-text-chars
  "Cap on stored raw OCR text. Enough to preserve the exact evidence behind
  a :healthy/:degraded verdict (e.g. which provider cards were or were not
  read) without unbounded growth of the local event store."
  4000)

(defn bounded-ocr-text
  "Truncate OCR text to `max-ocr-text-chars` so `analyze!` can attach it as
  replayable diagnostic evidence for every Observatory verdict."
  [text]
  (subs text 0 (min (count text) max-ocr-text-chars)))

(defn analyze! [project capture]
  (if-not (= :captured (:visual/status capture))
    capture
    (try
      (let [metrics (canvas-metrics project (:visual/path capture))
            ocr (execute-with-timeout
                 ["swift" "-e" ocr-script (:visual/path capture)]
                 project 20)
            _ (when-not (zero? (:exit ocr))
                (throw (ex-info "Vision OCR analysis failed"
                                {:exit (:exit ocr) :error (:err ocr)})))
            text (str/lower-case (:out ocr))]
        ;; Store the OCR text alongside the verdict so a :degraded finding
        ;; (e.g. "provider usage cards not visible") carries the evidence
        ;; that produced it, instead of forcing a blind re-run to diagnose.
        (merge capture metrics (evaluate-observatory metrics text)
               {:visual/ocr-text (bounded-ocr-text text)}))
      (catch Exception error
        (assoc capture :visual/status :analysis-failed
               :visual/error (.getMessage error))))))

(defn observe! [project state-root now-ms]
  (analyze! project (capture! state-root now-ms)))
