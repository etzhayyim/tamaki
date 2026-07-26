(ns kotoba.tamaki.visual
  "Screenshot and deterministic visual feedback for the Observatory loop."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kotoba.tamaki.delivery :as delivery])
  (:import [java.util.concurrent TimeUnit]
           [javax.imageio ImageIO]))

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

(defn- canvas-metrics [path]
  (let [image (ImageIO/read (io/file path))
        width (.getWidth image)
        height (.getHeight image)
        x0 (int (* width 0.08))
        x1 (int (* width 0.72))
        y0 (int (* height 0.23))
        y1 (int (* height 0.88))
        values (for [y (range y0 y1 8)
                     x (range x0 x1 8)
                     :let [rgb (.getRGB image x y)
                           r (bit-and 255 (bit-shift-right rgb 16))
                           g (bit-and 255 (bit-shift-right rgb 8))
                           b (bit-and 255 rgb)]]
                 {:luma (/ (+ (* 299 r) (* 587 g) (* 114 b)) 1000.0)
                  :spread (- (max r g b) (min r g b))})
        n (max 1 (count values))
        mean (/ (reduce + (map :luma values)) n)
        variance (/ (reduce + (map #(let [d (- (:luma %) mean)] (* d d))
                                   values))
                    n)]
    {:canvas/luma mean
     :canvas/stddev (Math/sqrt variance)
     :canvas/color-ratio (/ (count (filter #(> (:spread %) 18) values))
                            (double n))
     :image/width width
     :image/height height}))

(defn analyze! [project capture]
  (if-not (= :captured (:visual/status capture))
    capture
    (try
      (let [metrics (canvas-metrics (:visual/path capture))
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
