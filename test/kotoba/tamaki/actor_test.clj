(ns kotoba.tamaki.actor-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
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

(deftest relative-actor-project-is-canonicalized-at-the-file-boundary
  (let [file (java.io.File/createTempFile "tamaki-actor-" ".edn")]
    (spit file (pr-str (assoc spec :actor/project ".")))
    (is (= (.getCanonicalPath (io/file "."))
           (:actor/project (actor/read-spec (.getPath file)))))))

(deftest reconcile-plan-scales-to-desired-state
  (let [run-0 (actor/replica-run spec 0 1)
        run-1 (assoc (actor/replica-run spec 1 2)
                     :agent.run/status :running)
        plan (actor/reconcile-plan spec [run-0 run-1])]
    (is (= 3 (:desired plan)))
    (is (= 1 (:queued plan)))
    (is (= 1 (:running plan)))
    (is (zero? (:blocked plan)))
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

(deftest effective-desired-scales-up-under-pressure
  (let [scaled (assoc-in spec [:actor/scale]
                         {:min 1 :desired 2 :max 5
                          :scale-up-on {:queue-depth 3 :blocker-count 2}
                          :scale-down-after-ms 300000})
        held [(assoc (actor/replica-run scaled 0 1)
                     :agent.run/status :held)
              (assoc (actor/replica-run scaled 1 2)
                     :agent.run/status :held)]]
    (testing "held replicas at the blocker threshold raise capacity"
      (is (= 4 (actor/effective-desired scaled held)))
      (let [plan (actor/reconcile-plan scaled held)]
        (is (= 4 (:desired plan)))
        (is (= 2 (:blocked plan)))
        (is (= 2 (:spawn plan)))))
    (testing "queued backlog at the queue-depth threshold raises capacity"
      (let [backlog (mapv #(actor/replica-run scaled % (+ 10 %)) (range 2))
            tight (assoc-in scaled [:actor/scale :scale-up-on :queue-depth] 2)]
        (is (= 3 (actor/effective-desired tight backlog)))
        (is (= 1 (:spawn (actor/reconcile-plan tight backlog))))))
    (testing "effective desired never exceeds :max"
      (let [many-held (mapv #(assoc (actor/replica-run scaled % %)
                                    :agent.run/status :held)
                            (range 4))]
        (is (= 5 (actor/effective-desired scaled many-held)))))))

(deftest effective-desired-scales-on-business-control-pressure
  (let [controlled (-> spec
                       (assoc-in [:actor/scale :scale-up-on
                                  :business-pressure] 0.65)
                       (assoc :actor/control-pressure 0.8))]
    (is (= 5 (actor/effective-desired controlled [])))
    (is (= 3 (actor/effective-desired
              (assoc controlled :actor/control-pressure 0.2) [])))))

(deftest scale-down-honours-idle-grace-period
  (let [scaled (assoc-in spec [:actor/scale]
                         {:min 1 :desired 1 :max 5
                          :scale-down-after-ms 300000})
        runs [(assoc (actor/replica-run scaled 0 1000)
                     :agent.run/updated-at 1000)
              (assoc (actor/replica-run scaled 1 1000)
                     :agent.run/updated-at 1000)
              (assoc (actor/replica-run scaled 2 1000)
                     :agent.run/status :running
                     :agent.run/updated-at 1000)]]
    (testing "excess queued replicas are not cancelled before the grace period"
      (is (= [] (:cancel (actor/reconcile-plan scaled runs 200000)))))
    (testing "once idle long enough, safe scale-down candidates are cancelled"
      (let [plan (actor/reconcile-plan scaled runs 400000)]
        (is (= 2 (count (:cancel plan))))
        (is (= 0 (:spawn plan)))))))

(deftest reconcile-reaps-ghost-runs-and-restores-capacity
  (let [one (assoc-in spec [:actor/scale] {:min 1 :desired 1 :max 2})
        stale (assoc (actor/replica-run one 0 1000)
                     :agent.run/status :running
                     :agent.run/updated-at 1000
                     :agent.run/budget {:deadline-ms 1000})
        before-expiry (actor/reconcile-plan one [stale] 120000)
        after-expiry (actor/reconcile-plan one [stale] 124000)]
    (is (= [] (:reap before-expiry)))
    (is (zero? (:spawn before-expiry)))
    (is (= [(:agent.run/id stale)] (:reap after-expiry)))
    (is (= 1 (:spawn after-expiry)))
    (is (zero? (:running after-expiry)))))
