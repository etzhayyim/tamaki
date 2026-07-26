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
