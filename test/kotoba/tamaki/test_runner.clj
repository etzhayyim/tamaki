(ns kotoba.tamaki.test-runner
  (:require [clojure.test :as test]
            [kotoba.tamaki.adapters-test]
            [kotoba.tamaki.model-test]
            [kotoba.tamaki.store-test])
  (:gen-class))

(defn -main [& _]
  (let [result (test/run-tests 'kotoba.tamaki.model-test
                               'kotoba.tamaki.adapters-test
                               'kotoba.tamaki.store-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
