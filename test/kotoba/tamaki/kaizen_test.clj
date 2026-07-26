(ns kotoba.tamaki.kaizen-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.tamaki.actor :as actor]
            [kotoba.tamaki.kaizen :as kaizen]))

(defn event [kind at]
  {:tamaki.event/kind kind :tamaki.event/at at})

(deftest detects-review-integration-bottleneck
  (let [result (kaizen/evaluate
                [(event :run/started 900)
                 (event :patch/created 910)
                 (event :review/independent 920)]
                [] 1000 200)]
    (is (= :heal-review-integration-bottleneck
           (:kaizen/decision result)))
    (is (= 0.0 (get-in result
                       [:kaizen/evidence :review->integrate])))
    (is (:kaizen/requires-approval result))))

(deftest recommends-continuation-only-after-integration
  (let [result (kaizen/evaluate
                [(event :run/started 900)
                 (event :patch/created 910)
                 (event :review/independent 920)
                 (event :patch/integrated 930)]
                [] 1000 200)]
    (is (= :continue (:kaizen/decision result)))
    (is (= 1.0 (get-in result
                       [:kaizen/evidence :review->integrate])))))

(deftest loop-evaluator-replicas-are-observe-only
  (let [run (actor/replica-run
             {:actor/id :tamaki/loop-gardener
              :actor/project "."
              :actor/objective "Evaluate loop outputs"
              :actor/capabilities #{:loop-evaluation :event-observation}
              :actor/hil-policy {:change-authority :blocked}
              :actor/scale {:min 1 :desired 1 :max 1}
              :actor/runners [{:runner :codex :weight 1}]}
             0 1000)]
    (is (:agent.run/require-done-no-edit? run))))
