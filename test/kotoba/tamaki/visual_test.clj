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

(deftest window-found-and-screencapture-succeeds-yields-a-screen-capture-image
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "tamaki-visual-test"
               (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-redefs [kotoba.tamaki.delivery/execute!
                  (fn [argv]
                    (case (first argv)
                      "swift" {:exit 0 :out "42\n" :err ""}
                      "screencapture" (do (spit (last argv) "png-bytes")
                                          {:exit 0 :out "" :err ""})
                      "sips" {:exit 0 :out "" :err ""}
                      {:exit 1 :out "" :err "unexpected command"}))]
      (let [result (visual/capture! root (System/currentTimeMillis))]
        (is (= :captured (:visual/status result)))
        (is (= :screen-capture (:visual/source result)))
        (is (= "42" (:visual/window-id result)))
        (is (pos? (:visual/bytes result)))))))

(deftest window-found-but-screencapture-failure-is-an-observable-nonfatal-state
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "tamaki-visual-test"
               (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-redefs [kotoba.tamaki.delivery/execute!
                  (fn [argv]
                    (if (= (first argv) "swift")
                      {:exit 0 :out "7\n" :err ""}
                      {:exit 1 :out "" :err "screen recording permission denied"}))]
      (let [result (visual/capture! root (System/currentTimeMillis))]
        (is (= :unavailable (:visual/status result)))
        (is (= "screen recording permission denied"
               (:visual/error result)))))))
