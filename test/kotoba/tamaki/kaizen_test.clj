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
                 (assoc (event :patch/integrated 930)
                        :tamaki.event/data {:patch/id "p1"})
                 (assoc (event :result/evaluated 940)
                        :tamaki.event/data
                        {:evaluation/result "result/p1"})]
                [] 1000 200)]
    (is (= :continue (:kaizen/decision result)))
    (is (= 1.0 (get-in result
                       [:kaizen/evidence :review->integrate])))))

(deftest integration-without-evaluation-becomes-control-priority
  (let [result
        (kaizen/evaluate
         [(assoc (event :patch/integrated 900)
                 :tamaki.event/data {:patch/id "p1"})]
         [] 1000 200)]
    (is (= :evaluate-integrated-results (:kaizen/decision result)))
    (is (= ["result/p1"]
           (get-in result [:kaizen/evidence :evaluation-debt])))))

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
                 (assoc (event :run/failed 910)
                        :tamaki.event/data
                        {:failure/category :verification-failed})
                 (assoc (event :run/failed 920)
                        :tamaki.event/data
                        {:failure/category :verification-failed})]
                [] 1000 200)]
    (is (= :throttle-spawn (:kaizen/decision result)))
    (is (= (/ 2.0 3.0)
           (get-in result [:kaizen/evidence :failure-pressure])))))

(deftest a-single-transient-failure-does-not-deadlock-the-fleet
  (let [result (kaizen/evaluate
                [(event :run/started 900)
                 (event :run/failed 910)]
                [] 1000 200)]
    (is (not-any? #{:throttle-spawn}
                  (:kaizen/recommendations result)))))

(deftest unknown-and-human-gate-failures-do-not-apply-global-brake
  (let [result
        (kaizen/evaluate
         [(event :run/started 900)
          (event :run/failed 910)
          (assoc (event :run/failed 920)
                 :tamaki.event/data {:reason :approval-required})]
         [] 1000 200)]
    (is (= 2 (get-in result [:kaizen/evidence :failures])))
    (is (zero? (get-in result [:kaizen/evidence :throttle-failures])))
    (is (not-any? #{:throttle-spawn}
                  (:kaizen/recommendations result)))))

(deftest failure-pressure-keeps-essential-support-and-incident-work-alive
  (let [evaluation {:kaizen/recommendations
                    [:evaluate-integrated-results :throttle-spawn]}
        support (kaizen/spawn-admission
                 evaluation []
                 {:actor/capabilities
                  #{:implementation :support-routing}})
        ordinary (kaizen/spawn-admission
                  evaluation []
                  {:actor/capabilities #{:implementation :review}})]
    (is (:admitted? support))
    (is (= :essential-operations (:reason support)))
    (is (re-find #"Essential operations mode"
                 (:objective-prefix support)))
    (is (false? (:admitted? ordinary)))
    (is (= :failure-pressure (:reason ordinary)))))

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

(deftest spawn-admission-enforces-global-wip-and-keeps-observer-alive
  (let [evaluation {:kaizen/recommendations []}
        runs (repeat 4 {:agent.run/status :running})
        implementation {:actor/capabilities #{:implementation}}
        observer {:actor/capabilities #{:loop-evaluation}}]
    (is (= :global-wip-limit
           (:reason (kaizen/spawn-admission evaluation runs implementation))))
    (is (false? (:admitted?
                 (kaizen/spawn-admission evaluation runs implementation))))
    ;; The organism's sensing/control lane has a separate bounded reserve, so
    ;; implementation saturation cannot blind its own governor.
    (is (:admitted? (kaizen/spawn-admission evaluation runs observer)))
    (is (:admitted? (kaizen/spawn-admission evaluation [] observer)))))

(deftest control-lane-is-bounded-separately-from-work
  (let [evaluation {:kaizen/recommendations []}
        observer {:actor/capabilities #{:loop-evaluation}}
        control-run {:agent.run/status :running
                     :agent.run/required-capabilities #{:loop-evaluation}}]
    (is (:admitted?
         (kaizen/spawn-admission evaluation [control-run] observer)))
    (is (= :global-wip-limit
           (:reason
            (kaizen/spawn-admission
             evaluation [control-run control-run] observer))))))

(deftest review-bottleneck-admits-only-review-capable-work
  (let [evaluation {:kaizen/recommendations
                    [:heal-review-integration-bottleneck]}
        implement {:actor/capabilities #{:implementation}}
        reviewer {:actor/capabilities #{:implementation :review}}
        rejected (kaizen/spawn-admission evaluation [] implement)
        admitted (kaizen/spawn-admission evaluation [] reviewer)]
    (is (= :review-integration-drain (:reason rejected)))
    (is (false? (:admitted? rejected)))
    (is (= :review-priority (:reason admitted)))
    (is (re-find #"do not start a new issue"
                 (:objective-prefix admitted)))))

(deftest low-yield-starts-also-redirect-capacity-to-existing-results
  (let [evaluation {:kaizen/recommendations
                    [:redirect-issue-selection :prune-no-change-loop]}
        admitted (kaizen/spawn-admission
                  evaluation []
                  {:actor/capabilities #{:implementation :review}})]
    (is (:admitted? admitted))
    (is (= :review-priority (:reason admitted)))
    (is (some? (:objective-prefix admitted)))))

(deftest failed-events-retain-actionable-categories
  (let [result (kaizen/evaluate
                [(assoc (event :run/failed 900)
                        :tamaki.event/data {:actor/reason :lease-expired})
                 (assoc (event :run/failed 910)
                        :tamaki.event/data
                        {:failure/category :verification-failed})]
                [] 1000 200)]
    (is (= {:stale 1 :verification 1}
           (get-in result
                   [:kaizen/evidence :failure-categories])))))
