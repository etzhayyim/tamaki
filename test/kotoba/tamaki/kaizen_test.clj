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

(deftest detects-stale-runs-and-recommends-healing
  (let [stale-run {:agent.run/id "run-x"
                   :agent.run/status :running
                   :agent.run/updated-at 0
                   :agent.run/budget {:deadline-ms 1000}}
        result (kaizen/evaluate [] [stale-run] 121000 200)]
    (is (= :heal-stale-runs (:kaizen/decision result)))
    (is (= ["run-x"] (get-in result [:kaizen/evidence :stale-runs])))))

(deftest high-failure-pressure-throttles-spawn
  (let [result (kaizen/evaluate
                [(event :run/started 900)
                 (event :run/failed 910)]
                [] 1000 200)]
    (is (= :throttle-spawn (:kaizen/decision result)))
    (is (= 0.5 (get-in result [:kaizen/evidence :failure-pressure])))))

(deftest low-throughput-after-several-starts-redirects-issue-selection
  (let [result (kaizen/evaluate
                [(event :run/started 900)
                 (event :run/started 910)
                 (event :run/started 920)
                 (event :run/started 930)]
                [] 1000 200)]
    (is (= :redirect-issue-selection (:kaizen/decision result)))
    (is (= 0.0 (get-in result [:kaizen/evidence :start->patch])))))

(deftest no-patches-from-a-single-start-recommends-pruning
  (let [result (kaizen/evaluate
                [(event :run/started 900)]
                [] 1000 200)]
    (is (= :prune-no-change-loop (:kaizen/decision result)))))

(deftest idle-window-with-no-signal-recommends-observation
  (let [result (kaizen/evaluate [] [] 1000 200)]
    (is (= :observe (:kaizen/decision result)))
    (is (zero? (:kaizen/score result)))))
