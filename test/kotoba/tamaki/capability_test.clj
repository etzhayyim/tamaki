(ns kotoba.tamaki.capability-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.actor :as actor]
            [kotoba.tamaki.capability :as capability]))

(def heartbeat-execution
  {:execution/substrate :kototama-wasm
   :execution/role :control-guest
   :execution/realizes #{:organism/heartbeat}
   :execution/capability-contract
   {:contract/version 1
    :abi/namespace "actor:host"
    :abi/version 0
    :imports #{:clock-monotonic :sha256-hex :log-write}
    :grants #{:clock-monotonic :sha256-hex :log-write}
    :limits {:allow-write-imports? true
             :allow-secret-imports? false
             :max-http-posts 0
             :max-http-fetches 0
             :max-llm-infers 0
             :allowed-url-prefixes []}
    :effect-policy {:clock :autonomous
                    :crypto :autonomous
                    :storage-write :autonomous}}})

(deftest heartbeat-contract-is-explicit-and-valid
  (let [report (capability/validate-contract
                #{:organism/heartbeat} heartbeat-execution)]
    (is (:ok? report))
    (is (= #{:clock-monotonic :sha256-hex :log-write}
           (:required-imports report)))
    (is (= #{:clock :crypto :storage-write} (:effects report)))))

(deftest envelope-exposes-only-runtime-authority
  (let [envelope (capability/execution-envelope
                  :organism/heartbeat
                  #{:organism/heartbeat}
                  heartbeat-execution)]
    (is (= 1 (:tamaki.capability/version envelope)))
    (is (= "actor:host"
           (get-in envelope [:tamaki.capability/abi :namespace])))
    (is (= #{:clock-monotonic :sha256-hex :log-write}
           (:tamaki.capability/imports envelope)))
    (is (= #{"kotoba-lang/capability-clock-monotonic"
             "kotoba-lang/capability-hash-sha256"
             "kotoba-lang/capability-log-write"}
           (set (map :capability/repository
                     (:tamaki.capability/repositories envelope)))))
    (is (every? string?
                (map :capability/definition-cid
                     (:tamaki.capability/repositories envelope))))
    (is (every? string?
                (map :capability/hash-contract-cid
                     (:tamaki.capability/repositories envelope))))
    (is (nil? (:actor/objective envelope)))
    (is (nil? (:actor/capabilities envelope)))))

(deftest authority-never-follows-business-capability-implicitly
  (let [report (capability/validate-contract
                #{:implementation}
                (assoc heartbeat-execution
                       :execution/realizes #{:implementation}))]
    (is (false? (:ok? report)))
    (is (some #(= :capabilities/unrealizable (:error %))
              (:errors report)))))

(deftest grants-limits-and-effects-fail-closed
  (testing "a requested host import needs an authority grant"
    (let [execution (update-in heartbeat-execution
                               [:execution/capability-contract :grants]
                               disj :log-write)
          report (capability/validate-contract
                  #{:organism/heartbeat} execution)]
      (is (some #(= :grants/missing (:error %)) (:errors report)))))
  (testing "network access needs both a bounded allowlist and a human policy"
    (let [execution
          (assoc heartbeat-execution
                 :execution/realizes #{:network/post}
                 :execution/capability-contract
                 {:contract/version 1
                  :abi/namespace "actor:host" :abi/version 0
                  :imports #{:http-post} :grants #{:http-post}
                  :limits {:max-http-posts 1
                           :allowed-url-prefixes nil}
                  :effect-policy {:network-write :autonomous}})
          report (capability/validate-contract #{:network/post} execution)
          kinds (set (map :error (:errors report)))]
      (is (contains? kinds :network/allowlist-required))
      (is (contains? kinds :effect-policy/network-write-needs-human)))))

(deftest actor-spec-rejects-an-invalid-wasm-contract-before-placement
  (let [spec {:actor/id :organism/heartbeat
              :actor/project "/repo"
              :actor/objective "emit a bounded pulse"
              :actor/capabilities #{:organism/heartbeat}
              :actor/execution
              (update-in heartbeat-execution
                         [:execution/capability-contract :imports]
                         conj :ambient-shell)
              :actor/hil-policy {:external-effect :approval-required}
              :actor/scale {:min 0 :desired 1 :max 1}
              :actor/runners [{:runner :kototama :weight 1}]}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"capability contract rejected"
         (actor/validate-spec spec)))))
