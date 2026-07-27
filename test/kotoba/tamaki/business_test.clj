(ns kotoba.tamaki.business-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.business :as business]))

(def targets
  {:target/mrr-jpy 1000000
   :target/risk-adjusted-delta-mrr-jpy 100000
   :target/activation-rate 0.35
   :target/paid-conversion-rate 0.10
   :target/max-churn-rate 0.03
   :target/experiments-per-week 3
   :target/max-agent-cost-per-patch-jpy 5000})

(def observation
  {:period-days 7
   :stocks {:traffic 1000 :qualified-leads 40 :conversations 20
            :proposals 10 :won-customers 4 :active-customers 20
            :mrr-jpy 800000 :cash-jpy 2000000}
   :flows {:new-qualified-leads 40 :new-conversations 20
           :new-proposals 10 :new-wins 4 :activations 14
           :churned-customers 1 :delta-mrr-jpy 150000
           :experiments-shipped 3 :accepted-patches 2
           :agent-cost-jpy 4000 :operational-cost-jpy 10000}
   :rates {:confidence 0.8}})

(deftest durable-observations-produce-business-control-state
  (let [events [(business/event observation 1)]
        summary (business/summary events targets)]
    (is (= :observed (:business/status summary)))
    (is (= 800000.0 (get-in summary [:business/kpis :mrr-jpy])))
    (is (= 0.35 (get-in summary [:business/kpis :activation-rate])))
    (is (= 0.1 (get-in summary [:business/kpis :paid-conversion-rate])))
    (is (= 106000.0
           (get-in summary
                   [:business/kpis :risk-adjusted-delta-mrr-jpy])))
    (is (<= 0.0 (:business/control-score summary) 1.0))))

(deftest missing-observation-is-unknown-and-creates-pressure
  (let [summary (business/summary [] targets)
        signals (business/control-signals summary)]
    (is (= :unobserved (:business/status summary)))
    (is (= 0.0 (:business/control-score summary)))
    (is (= 1.0 (:business-pressure signals)))
    (is (zero? (:confidence signals)))))

(deftest stale-observation-produces-stale-status-without-fabricated-kpis
  (let [stale-observation (assoc observation :fresh? false)
        events [(business/event stale-observation 1)]
        summary (business/summary events targets)
        signals (business/control-signals summary)]
    (is (= :stale (:business/status summary)))
    (is (= {} (:business/kpis summary)))
    (is (= {} (:business/progress summary)))
    (is (= 0.0 (:business/control-score summary)))
    (testing "stale data raises pressure like an unobserved baseline instead of reporting fabricated confidence"
      (is (= 1.0 (:business-pressure signals)))
      (is (zero? (:confidence signals))))))

(deftest portfolio-sums-latest-fresh-domain-facts-and-discloses-stale-domains
  (let [events [(business/event
                 (assoc observation :domain :alpha :fresh? true
                        :stocks (assoc (:stocks observation) :traffic 100))
                 1)
                (business/event
                 (assoc observation :domain :alpha :fresh? true
                        :stocks (assoc (:stocks observation) :traffic 200))
                 2)
                (business/event
                 (assoc observation :domain :beta :fresh? true
                        :stocks (assoc (:stocks observation) :traffic 300))
                 3)
                (business/event
                 (assoc observation :domain :stale :fresh? false
                        :stocks (assoc (:stocks observation) :traffic 9999))
                 4)]
        summary (business/summary events targets)
        portfolio (:business/observation summary)]
    (is (= :observed (:business/status summary)))
    (is (= 500.0 (get-in summary [:business/kpis :traffic])))
    (is (= [:alpha :beta] (:domains portfolio)))
    (is (= [:stale] (:stale-domains portfolio)))
    (is (= 2 (count (:domains portfolio))))))

(deftest stock-flow-is-derived-only-from-durable-facts
  (let [dynamics (business/stock-flow [(business/event observation 1)]
                                      targets)]
    (is (= 5 (count (:stocks dynamics))))
    (is (= 5 (count (:flows dynamics))))
    (is (= 800000.0 (:value (last (:stocks dynamics)))))
    (testing "risk and cost reduce raw MRR growth"
      (is (< (business/risk-adjusted-delta-mrr
              (business/normalize-observation observation))
             150000)))))

(deftest read-targets-loads-a-valid-edn-map
  ;; `read-targets` backs `tamaki kpi status|observe|collect` and actor
  ;; reconciliation (`cli.clj` `business-targets`); it had no direct test
  ;; coverage even though it enforces a real shape guard below.
  (let [file (java.io.File/createTempFile "tamaki-business-targets" ".edn")]
    (try
      (spit file (pr-str targets))
      (is (= targets (business/read-targets (.getAbsolutePath file))))
      (finally (.delete file)))))

(deftest read-targets-rejects-a-non-map-edn-document
  (let [file (java.io.File/createTempFile "tamaki-business-targets" ".edn")]
    (try
      (spit file (pr-str [:target/mrr-jpy 1000000]))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"must be an EDN map"
           (business/read-targets (.getAbsolutePath file))))
      (finally (.delete file)))))
