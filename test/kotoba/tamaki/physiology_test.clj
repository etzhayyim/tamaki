(ns kotoba.tamaki.physiology-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.physiology :as physiology]))

(def policy
  {:organism/id :tamaki/hikari
   :organism/expires-at 10000
   :homeostasis/targets
   {:inference-token-reserve 10000
    :storage-free-bytes 1000000
    :durable-replicas 3
    :treasury-runway-days 30}
   :economic/policy
   {:economic/mode :earn-useful-work
    :allow-mining? false
    :allow-self-mint? false
    :auto-settle-crypto? false}})

(def healthy
  {:organism/id :tamaki/hikari
   :availability 1.0
   :inference-token-reserve 10000
   :storage-free-bytes 1000000
   :durable-replicas 3
   :treasury-runway-days 30
   :human-authority-valid? true})

(deftest exhausted-stocks-control-behaviour
  (is (= :work (:homeostasis/status
                (physiology/decide policy healthy 1000))))
  (is (= :preserve-memory
         (:homeostasis/status
          (physiology/decide policy (assoc healthy :durable-replicas 1) 1000))))
  (is (= :cognitive-rest
         (:homeostasis/status
          (physiology/decide policy
                             (assoc healthy :inference-token-reserve 1000)
                             1000)))))

(deftest earning-is-demand-and-proof-gated
  (let [low-runway (assoc healthy :treasury-runway-days 5)
        offer (physiology/decide policy low-runway 1000)
        settlement (physiology/decide
                    policy
                    (assoc low-runway :paid-demand? true
                           :useful-work-receipt-valid? true)
                    1000)]
    (is (= :publish-useful-work-offer (:homeostasis/action offer)))
    (is (= :request-settlement-approval
           (:homeostasis/action settlement)))
    (is (:homeostasis/hil-required? settlement))
    (is (false? (get-in settlement
                        [:homeostasis/authority :may-self-mint?])))))

(deftest lease-and-human-authority-dominate-survival-pressure
  (testing "an expired organism cannot use homeostasis to extend itself"
    (let [result (physiology/decide policy healthy 10000)]
      (is (= :expired (:homeostasis/status result)))
      (is (= :terminate (:homeostasis/action result)))))
  (testing "loss of human authority pauses normal activity"
    (is (= :paused
           (:homeostasis/status
            (physiology/decide
             policy (assoc healthy :human-authority-valid? false) 1000))))))

(deftest human-authority-attestation-can-expire
  (let [fresh-policy (assoc policy :human-authority-max-age-ms 500)
        observation (assoc healthy :human-authority-observed-at 1000)]
    (is (= :work
           (:homeostasis/status
            (physiology/decide fresh-policy observation 1400))))
    (is (= :paused
           (:homeostasis/status
            (physiology/decide fresh-policy observation 1600))))))

(deftest physiology-controls-new-agent-inference
  (let [ordinary {:actor/capabilities #{:implementation}}
        economy {:actor/capabilities #{:implementation :useful-work-offer}}]
    (is (:admitted?
         (physiology/agent-admission
          {:homeostasis/status :work} ordinary)))
    (is (not (:admitted?
              (physiology/agent-admission
               {:homeostasis/status :reclaim-storage
                :homeostasis/action :compact-derived-projections}
               ordinary))))
    (is (not (:admitted?
              (physiology/agent-admission
               {:homeostasis/status :earn} ordinary))))
    (is (:admitted?
         (physiology/agent-admission
          {:homeostasis/status :earn} economy)))))

(deftest storage-pressure-routes-to-the-deterministic-curator
  (let [projection
        (physiology/decide
         policy
         (assoc healthy :storage-free-bytes 50000)
         1000)]
    (is (= :reclaim-storage (:homeostasis/status projection)))
    (is (= :reconcile-storage-policy (:homeostasis/action projection)))))

(deftest crypto-mining-and-self-minting-fail-closed
  (is (thrown? Exception
               (physiology/validate-policy
                (assoc-in policy [:economic/policy :allow-mining?] true))))
  (is (thrown? Exception
               (physiology/validate-policy
                (assoc-in policy [:economic/policy :allow-self-mint?] true))))
  (is (thrown? Exception
               (physiology/validate-policy
                (assoc-in policy
                          [:economic/policy :auto-settle-crypto?] true)))))
