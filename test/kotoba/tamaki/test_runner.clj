(ns kotoba.tamaki.test-runner
  (:require [clojure.test :as test]
            [kotoba.tamaki.adapters-test]
            [kotoba.tamaki.cli-test]
            [kotoba.tamaki.delivery-test]
            [kotoba.tamaki.loop-test]
            [kotoba.tamaki.intelligence-test]
            [kotoba.tamaki.model-test]
            [kotoba.tamaki.runners-test]
            [kotoba.tamaki.store-test]
            [kotoba.tamaki.visual-test])
  (:gen-class))

(defn run [_]
  (let [result (test/run-tests 'kotoba.tamaki.model-test
                               'kotoba.tamaki.adapters-test
                               'kotoba.tamaki.cli-test
                               'kotoba.tamaki.delivery-test
                               'kotoba.tamaki.loop-test
                               'kotoba.tamaki.intelligence-test
                               'kotoba.tamaki.runners-test
                               'kotoba.tamaki.store-test
                               'kotoba.tamaki.visual-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (throw (ex-info "Tamaki tests failed" result)))
    result))

(defn -main [& _]
  (try
    (run nil)
    (catch Exception _
      (System/exit 1))))
