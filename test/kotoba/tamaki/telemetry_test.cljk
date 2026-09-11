(ns kotoba.tamaki.telemetry-test
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest mapping-can-scale-provider-units-without-provider-specific-code
  (let [file (java.io.File/createTempFile "tamaki-scaled-" ".edn")
        now 1785024000000]
    (spit file (pr-str {:as-of "2026-07-25"
                        :conversion {:percent 12.5}}))
    (let [result (telemetry/collect
                  {:collector/id :scaled
                   :collector/domain :example.test
                   :collector/source (.getPath file)
                   :collector/mappings
                   {:rates {:paid-conversion-rate
                            {:path [:conversion :percent]
                             :scale 0.01}}}}
                  now)]
      (is (= 0.125
             (get-in result
                     [:observation :rates :paid-conversion-rate]))))))

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

(deftest observed-at-accepts-iso-instants-and-epoch-ms
  (testing "ISO-8601 instants from provider exports remain fresh within max-age"
    (let [iso-ms (telemetry/observed-at-ms
                  {:observed-at "2026-07-26T08:00:00Z"})]
      (is (number? iso-ms))
      (is (= iso-ms
             (telemetry/observed-at-ms
              {:observed-at "2026-07-26T08:00:00Z"}))))
    (let [file (java.io.File/createTempFile "tamaki-iso-" ".edn")
          now 1785052800000]
      (spit file (pr-str {:observed-at "2026-07-26T07:00:00Z"
                          :mrr 42}))
      (let [result (telemetry/collect
                    {:collector/id :iso/provider
                     :collector/domain :example.test
                     :collector/source (.getPath file)
                     :collector/max-age-ms 7200000
                     :collector/mappings {:stocks {:mrr-jpy [:mrr]}}}
                    now)
            observed-at (get-in result [:observation :source-observed-at])]
        (is (:collector/fresh? result))
        (is (number? observed-at))
        (is (<= 0 (- now observed-at) 7200000))
        (is (= 42 (get-in result [:observation :stocks :mrr-jpy]))))))
  (testing "numeric and digit-string epoch milliseconds are accepted"
    (is (= 1785024000000
           (telemetry/observed-at-ms {:as-of 1785024000000})))
    (is (= 1785024000000
           (telemetry/observed-at-ms {:as-of "1785024000000"})))))

(deftest unparseable-timestamp-fails-closed-without-aborting-collect
  ;; A garbage timestamp must not throw out of collect: revenue control needs
  ;; a durable zero-confidence observation rather than a crashed KPI intake.
  ;; business/summary only treats explicit false as :stale, so fresh? must be
  ;; boolean false (not nil) when the provider clock cannot be trusted.
  (is (nil? (telemetry/observed-at-ms {:as-of "not-a-timestamp"})))
  (let [file (java.io.File/createTempFile "tamaki-bad-ts-" ".edn")]
    (spit file (pr-str {:as-of "next Tuesday" :value 7}))
    (let [result (telemetry/collect
                  {:collector/id :bad-ts
                   :collector/domain :example.test
                   :collector/source (.getPath file)
                   :collector/max-age-ms 172800000
                   :collector/mappings {:stocks {:traffic [:value]}}}
                  1785024000000)]
      (is (false? (:collector/fresh? result)))
      (is (false? (get-in result [:observation :fresh?])))
      (is (nil? (get-in result [:observation :source-observed-at])))
      (is (zero? (get-in result [:observation :rates :confidence])))
      (is (= 7 (get-in result [:observation :stocks :traffic]))))))

(deftest collector-requires-a-domain-for-portfolio-isolation
  (let [file (java.io.File/createTempFile "tamaki-no-domain-" ".edn")]
    (spit file (pr-str {:as-of "2026-07-25" :value 1}))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Collector requires :collector/domain"
         (telemetry/collect
          {:collector/id :missing-domain
           :collector/source (.getPath file)
           :collector/mappings {:stocks {:traffic [:value]}}}
          1785024000000)))))
