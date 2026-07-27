(ns kotoba.tamaki.visual-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kotoba.tamaki.delivery]
            [kotoba.tamaki.visual :as visual]))

(defn- write-solid-png!
  "Write the PNG signature and IHDR used by dimension-gate tests.
  Avoid AWT/ImageIO so the documented Babashka gate stays portable."
  [file width height]
  (with-open [out (java.io.DataOutputStream.
                   (io/output-stream (io/file file)))]
    (doseq [value [0x89 0x50 0x4e 0x47 0x0d 0x0a 0x1a 0x0a]]
      (.write out value))
    (.writeInt out 13)
    (.writeBytes out "IHDR")
    (.writeInt out (int width))
    (.writeInt out (int height))
    ;; bit depth, colour type, compression, filter, interlace + placeholder CRC
    (doseq [value [8 2 0 0 0]] (.write out value))
    (.writeInt out 0))
  file)

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

(deftest bounded-ocr-text-truncates-long-output
  ;; Unbounded OCR text must not be allowed to grow the local event store
  ;; without limit.
  (let [long-text (apply str (repeat 5000 "a"))
        bounded (visual/bounded-ocr-text long-text)]
    (is (= visual/max-ocr-text-chars (count bounded)))
    (is (= (subs long-text 0 visual/max-ocr-text-chars) bounded))))

(deftest bounded-ocr-text-preserves-short-output
  (is (= "tamaki observatory" (visual/bounded-ocr-text "tamaki observatory"))))

(deftest analyze-attaches-ocr-text-evidence-to-verdict
  ;; A :degraded verdict (e.g. this cycle's observed "provider usage cards
  ;; are not visible" finding) must carry the literal OCR text that produced
  ;; it. Without this, diagnosing a degraded Observatory requires a blind
  ;; re-capture instead of inspecting the durable record.
  (with-redefs [visual/execute-with-timeout
                (fn [argv _cwd _timeout-seconds]
                  (cond
                    (= (nth argv 2) visual/metrics-script)
                    {:exit 0 :out "1280 800 100.0 20.0 0.5" :err ""}

                    (= (nth argv 2) visual/ocr-script)
                    {:exit 0 :out "Tamaki Observatory Codex Claude Activity"
                     :err ""}

                    :else {:exit 1 :out "" :err "unexpected"}))]
    (let [result (visual/analyze! "." {:visual/status :captured
                                        :visual/path "observatory.png"})]
      (is (= :degraded (:visual/status result)))
      (is (= "tamaki observatory codex claude activity"
             (:visual/ocr-text result)))
      (is (some #(= "One or more provider usage cards are not visible" %)
                (:visual/findings result))))))

(deftest analyze-reports-ocr-process-failure
  ;; A failed or timed-out OCR subprocess must not masquerade as a successful
  ;; degraded verdict claiming that every text-dependent panel is absent.
  (with-redefs [visual/execute-with-timeout
                (fn [argv _cwd _timeout-seconds]
                  (if (= (nth argv 2) visual/metrics-script)
                    {:exit 0 :out "1280 800 100.0 20.0 0.5" :err ""}
                    {:exit 124 :out "" :err "visual analysis timed out"}))]
    (let [result (visual/analyze! "." {:visual/status :captured
                                         :visual/path "observatory.png"})]
      (is (= :analysis-failed (:visual/status result)))
      (is (= "Vision OCR analysis failed" (:visual/error result)))
      (is (nil? (:visual/findings result))))))

(deftest analyze-leaves-uncaptured-status-untouched
  (is (= {:visual/status :unavailable :visual/error "no window"}
         (visual/analyze! "." {:visual/status :unavailable
                                 :visual/error "no window"}))))
