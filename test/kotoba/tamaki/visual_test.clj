(ns kotoba.tamaki.visual-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kotoba.tamaki.delivery]
            [kotoba.tamaki.visual :as visual])
  (:import [java.awt.image BufferedImage]
           [javax.imageio ImageIO]))

(defn- write-solid-png!
  "Write a deterministic solid-color PNG at the given pixel size for capture tests."
  [file width height]
  (let [img (BufferedImage. (int width) (int height) BufferedImage/TYPE_INT_RGB)]
    (doto (.getGraphics img)
      (.setColor java.awt.Color/DARK_GRAY)
      (.fillRect 0 0 width height)
      (.dispose))
    (ImageIO/write img "png" (io/file file))
    file))

(deftest missing-window-is-an-observable-nonfatal-state
  (with-redefs [kotoba.tamaki.delivery/execute!
                (fn [& _] {:exit 0 :out "" :err ""})]
    (is (= :unavailable
           (:visual/status
            (visual/capture! (java.io.File. "/tmp") 1))))))

(deftest png-dimensions-reads-ihdr-without-decoding-pixels
  (let [file (java.io.File/createTempFile "tamaki-png-dim-" ".png")]
    (try
      (write-solid-png! file 1100 800)
      (is (= {:width 1100 :height 800} (visual/png-dimensions file)))
      (is (true? (visual/usable-webkit-snapshot? file)))
      (finally (.delete file)))))

(deftest undersized-png-is-not-a-usable-webkit-snapshot
  ;; Observed degraded Observatory frames are 767x548 WebKit live.png files
  ;; that omit provider usage cards; the gate must reject them.
  (let [file (java.io.File/createTempFile "tamaki-png-small-" ".png")]
    (try
      (write-solid-png! file 767 548)
      (is (= {:width 767 :height 548} (visual/png-dimensions file)))
      (is (false? (visual/usable-webkit-snapshot? file)))
      (finally (.delete file)))))

(deftest fresh-webkit-snapshot-is-preferred-without-screen-capture
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "tamaki-visual-test"
               (make-array java.nio.file.attribute.FileAttribute 0)))
        dir (java.io.File. root "visual")
        live (java.io.File. dir "live.png")
        called? (atom false)]
    (.mkdirs dir)
    (write-solid-png! live 1100 800)
    (with-redefs [kotoba.tamaki.delivery/execute!
                  (fn [& _] (reset! called? true)
                    {:exit 1 :out "" :err "unexpected"})]
      (let [result (visual/capture! root (System/currentTimeMillis))]
        (is (= :captured (:visual/status result)))
        (is (= :webkit (:visual/source result)))
        (is (= {:width 1100 :height 800}
               (visual/png-dimensions (io/file (:visual/path result)))))
        (is (false? @called?))))))

(deftest undersized-webkit-snapshot-falls-through-to-screen-capture
  ;; A fresh but too-small live.png must not short-circuit capture; otherwise
  ;; the visual loop reports false provider-card degradations forever.
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "tamaki-visual-undersized"
               (make-array java.nio.file.attribute.FileAttribute 0)))
        dir (java.io.File. root "visual")
        live (java.io.File. dir "live.png")
        calls (atom [])]
    (.mkdirs dir)
    (write-solid-png! live 767 548)
    (with-redefs [kotoba.tamaki.delivery/execute!
                  (fn [argv & _]
                    (swap! calls conj argv)
                    (cond
                      (= "swift" (first argv))
                      {:exit 0 :out "42\n" :err ""}
                      (= "screencapture" (first argv))
                      (do (write-solid-png! (last argv) 1280 900)
                          {:exit 0 :out "" :err ""})
                      (= "sips" (first argv))
                      {:exit 0 :out "" :err ""}
                      :else {:exit 1 :out "" :err "unexpected"}))]
      (let [result (visual/capture! root (System/currentTimeMillis))]
        (is (= :captured (:visual/status result)))
        (is (= :screen-capture (:visual/source result)))
        (is (= "42" (:visual/window-id result)))
        (is (some #(= "screencapture" (first %)) @calls))))))

(deftest evaluate-observatory-reports-healthy-when-all-checks-pass
  (let [metrics {:canvas/stddev 12.0}
        text "tamaki observatory codex claude grok activity"]
    (is (= {:visual/status :healthy
            :visual/findings []
            :visual/suggested-issue ""}
           (visual/evaluate-observatory metrics text)))))

(deftest evaluate-observatory-flags-blank-canvas
  (let [metrics {:canvas/stddev 2.0}
        text "tamaki observatory codex claude grok activity"
        result (visual/evaluate-observatory metrics text)]
    (is (= :degraded (:visual/status result)))
    (is (= ["3D canvas region appears blank or lacks grid contrast"]
           (:visual/findings result)))
    (is (= "Restore Observatory visual health: 3D canvas region appears blank or lacks grid contrast"
           (:visual/suggested-issue result)))))

(deftest evaluate-observatory-flags-missing-title
  (let [metrics {:canvas/stddev 12.0}
        text "codex claude grok activity"
        result (visual/evaluate-observatory metrics text)]
    (is (= :degraded (:visual/status result)))
    (is (= ["Observatory title was not recognized"] (:visual/findings result)))))

(deftest evaluate-observatory-flags-missing-provider-card
  (let [metrics {:canvas/stddev 12.0}
        text "tamaki observatory codex claude activity"
        result (visual/evaluate-observatory metrics text)]
    (is (= :degraded (:visual/status result)))
    (is (= ["One or more provider usage cards are not visible"]
           (:visual/findings result)))))

(deftest provider-usage-cards-visible-accepts-exact-labels
  (is (true? (visual/provider-usage-cards-visible?
              "tamaki observatory codex claude grok activity")))
  (is (false? (visual/provider-usage-cards-visible?
               "tamaki observatory codex claude activity"))))

(deftest provider-usage-cards-visible-tolerates-ocr-misread-of-grok
  ;; Observed Vision OCR of a healthy Observatory frame: purple 'grok' card
  ;; rendered as 'CrOR' while all four usage-stat blocks remained intact.
  (let [ocr (str "tamaki observatory "
                 "codex in 11427642 out 103128 "
                 "in 612 out 456879 "
                 "claude-zai in 4367435 out 286134 "
                 "cror in 320678 out 55493 "
                 "live activity")]
    (is (true? (visual/provider-usage-cards-visible? ocr)))
    (is (= :healthy
           (:visual/status
            (visual/evaluate-observatory {:canvas/stddev 12.0} ocr))))))

(deftest provider-usage-cards-visible-rejects-live-activity-only
  ;; Named providers can appear in the live activity stream without any
  ;; usage cards being on screen; stats must still back the fallback path.
  (is (false? (visual/provider-usage-cards-visible?
               "tamaki observatory codex claude activity token-processing"))))

(deftest evaluate-observatory-flags-missing-activity-panel
  (let [metrics {:canvas/stddev 12.0}
        text "tamaki observatory codex claude grok"
        result (visual/evaluate-observatory metrics text)]
    (is (= :degraded (:visual/status result)))
    (is (= ["Live activity panel was not recognized"] (:visual/findings result)))))

(deftest evaluate-observatory-joins-multiple-findings
  (let [metrics {:canvas/stddev 2.0}
        text ""
        result (visual/evaluate-observatory metrics text)]
    (is (= :degraded (:visual/status result)))
    (is (= 4 (count (:visual/findings result))))
    (is (= (str "Restore Observatory visual health: "
                "3D canvas region appears blank or lacks grid contrast; "
                "Observatory title was not recognized; "
                "One or more provider usage cards are not visible; "
                "Live activity panel was not recognized")
           (:visual/suggested-issue result)))))
