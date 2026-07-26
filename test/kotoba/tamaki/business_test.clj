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
