(ns kotoba.tamaki.telemetry-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.tamaki.telemetry :as telemetry]))

(deftest snapshot-mapping-preserves-facts-and-freshness
  (let [file (java.io.File/createTempFile "tamaki-telemetry-" ".edn")
        now 1785024000000]
    (spit file (pr-str {:as-of "2026-07-25"
                        :zone {:requests-7d 1200 :uniques-7d-sum 80}
                        :stripe {:revenue-jpy 5000}}))
    (let [result
          (telemetry/collect
           {:collector/id :example/provider
            :collector/domain :example.test
            :collector/source (.getPath file)
            :collector/max-age-ms 172800000
            :collector/mappings
            {:stocks {:traffic [:zone :requests-7d]
                      :active-customers [:zone :uniques-7d-sum]}
             :flows {:revenue-jpy [:stripe :revenue-jpy]}}}
           now)]
      (is (:collector/fresh? result))
      (is (= 1200 (get-in result [:observation :stocks :traffic])))
      (is (= 5000 (get-in result [:observation :flows :revenue-jpy]))))))

(deftest stale-snapshot-has-zero-confidence
  (let [file (java.io.File/createTempFile "tamaki-stale-" ".edn")]
    (spit file (pr-str {:as-of "2020-01-01" :value 1}))
    (let [result (telemetry/collect
                  {:collector/id :stale
                   :collector/domain :example.test
                   :collector/source (.getPath file)
                   :collector/max-age-ms 1000
                   :collector/mappings {:stocks {:traffic [:value]}}}
                  1785024000000)]
      (is (false? (:collector/fresh? result)))
      (is (zero? (get-in result [:observation :rates :confidence]))))))
