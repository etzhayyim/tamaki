(ns kotoba.tamaki.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.actor :as actor]))

(def spec
  {:actor/id :revenue/test
   :actor/project "/repo"
   :actor/objective "grow revenue safely"
   :actor/capabilities #{:triage}
   :actor/hil-policy {:integrate :voice-required}
   :actor/scale {:min 1 :desired 3 :max 5}
   :actor/runners [{:runner :codex :weight 2}
                   {:runner :claude :weight 1}]})

(deftest actor-spec-is-governed-and-weighted
  (is (= ["codex" "claude" "codex"] (actor/runner-pool spec)))
  (is (= :revenue/test (:actor/id (actor/validate-spec spec))))
  (testing "invalid desired state and policy are rejected"
    (is (thrown? Exception
                 (actor/validate-spec
                  (assoc-in spec [:actor/scale :desired] 9))))
    (is (thrown? Exception
                 (actor/validate-spec
                  (assoc-in spec [:actor/hil-policy :integrate] :silent))))))

(deftest reconcile-plan-scales-to-desired-state
  (let [run-0 (actor/replica-run spec 0 1)
        run-1 (assoc (actor/replica-run spec 1 2)
                     :agent.run/status :running)
        plan (actor/reconcile-plan spec [run-0 run-1])]
    (is (= 3 (:desired plan)))
    (is (= 1 (:queued plan)))
    (is (= 1 (:running plan)))
    (is (= 1 (:spawn plan)))
    (is (= :revenue/test (:agent.run/actor run-0)))
    (is (= "codex" (:agent.run/runner run-0)))))

(deftest reconcile-plan-selects-safe-scale-down-candidates
  (let [scaled (assoc-in spec [:actor/scale :desired] 1)
        runs [(actor/replica-run spec 0 1)
              (actor/replica-run spec 1 2)
              (assoc (actor/replica-run spec 2 3)
                     :agent.run/status :running)]
        plan (actor/reconcile-plan scaled runs)]
    (is (= 2 (count (:cancel plan))))
    (is (= 0 (:spawn plan)))))
