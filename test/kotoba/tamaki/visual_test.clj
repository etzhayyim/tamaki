(ns kotoba.tamaki.visual-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.tamaki.delivery]
            [kotoba.tamaki.visual :as visual]))

(deftest missing-window-is-an-observable-nonfatal-state
  (with-redefs [kotoba.tamaki.delivery/execute!
                (fn [& _] {:exit 0 :out "" :err ""})]
    (is (= :unavailable
           (:visual/status
            (visual/capture! (java.io.File. "/tmp") 1))))))

(deftest fresh-webkit-snapshot-is-preferred-without-screen-capture
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "tamaki-visual-test"
               (make-array java.nio.file.attribute.FileAttribute 0)))
        dir (java.io.File. root "visual")
        live (java.io.File. dir "live.png")
        called? (atom false)]
    (.mkdirs dir)
    (spit live "png")
    (with-redefs [kotoba.tamaki.delivery/execute!
                  (fn [& _] (reset! called? true)
                    {:exit 1 :out "" :err "unexpected"})]
      (let [result (visual/capture! root (System/currentTimeMillis))]
        (is (= :captured (:visual/status result)))
        (is (= :webkit (:visual/source result)))
        (is (= "png" (slurp (:visual/path result))))
        (is (false? @called?))))))

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
