(ns kotoba.tamaki.test-runner
  (:require [clojure.test :as test]
            [kotoba.tamaki.active-inference-test]
            [kotoba.tamaki.actor-test]
            [kotoba.tamaki.business-test]
            [kotoba.tamaki.bridge-test]
            [kotoba.tamaki.adapters-test]
            [kotoba.tamaki.cli-test]
            [kotoba.tamaki.delivery-test]
            [kotoba.tamaki.evolution-test]
            [kotoba.tamaki.loop-test]
            [kotoba.tamaki.loop-registry-test]
            [kotoba.tamaki.mail-test]
            [kotoba.tamaki.lineage-test]
            [kotoba.tamaki.intelligence-test]
            [kotoba.tamaki.kaizen-test]
            [kotoba.tamaki.model-test]
            [kotoba.tamaki.runners-test]
            [kotoba.tamaki.result-test]
            [kotoba.tamaki.store-test]
            [kotoba.tamaki.supervisor-test]
            [kotoba.tamaki.visual-test]
            [kotoba.tamaki.visibility-test])
  (:gen-class))

(defn run [_]
  (let [result (test/run-tests 'kotoba.tamaki.model-test
                               'kotoba.tamaki.active-inference-test
                               'kotoba.tamaki.actor-test
                               'kotoba.tamaki.business-test
                               'kotoba.tamaki.bridge-test
                               'kotoba.tamaki.adapters-test
                               'kotoba.tamaki.cli-test
                               'kotoba.tamaki.delivery-test
                               'kotoba.tamaki.evolution-test
                               'kotoba.tamaki.loop-test
                               'kotoba.tamaki.loop-registry-test
                               'kotoba.tamaki.mail-test
                               'kotoba.tamaki.lineage-test
                               'kotoba.tamaki.intelligence-test
                               'kotoba.tamaki.kaizen-test
                               'kotoba.tamaki.runners-test
                               'kotoba.tamaki.result-test
                               'kotoba.tamaki.store-test
                               'kotoba.tamaki.supervisor-test
                               'kotoba.tamaki.visual-test
                               'kotoba.tamaki.visibility-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (throw (ex-info "Tamaki tests failed" result)))
    result))

(defn -main [& _]
  (try
    (run nil)
    (catch Exception _
      (System/exit 1))))
