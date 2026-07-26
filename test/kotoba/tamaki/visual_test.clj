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
