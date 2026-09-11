(ns kotoba.tamaki.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.model :as model]))

(deftest run-id-validates-deterministic-entropy
  (is (= "run-1000-12345678"
         (model/run-id 1000 "1234-5678-90")))
  (is (thrown-with-msg? Exception
                        #"at least 8 characters"
                        (model/run-id 1000 "short"))))

(deftest agent-run-contract
  (let [run (model/agent-run {:goal "fix it"
                              :source-project "/source"
                              :project "/tmp/example"
                              :runner "claude-a"
                              :capabilities #{:git}}
                             1000)
        review (model/agent-run {:goal "review only"
                                 :require-done-no-edit? true}
                                1001)]
    (is (= :queued (:agent.run/status run)))
    (is (= "claude-a" (:agent.run/runner run)))
    (is (= "/source" (:agent.run/source-project run)))
    (is (= #{:git} (:agent.run/required-capabilities run)))
    (is (= 12 (get-in run [:agent.run/budget :max-turns])))
    (is (false? (:agent.run/require-done-no-edit? run))
        "implementation runs default to editable")
    (is (true? (:agent.run/require-done-no-edit? review))
        "observe-only review runs opt into DONE+no-edit")))

(deftest transition-gate
  (let [run (model/agent-run {:goal "fix it"} 1000)
        leased (model/transition run :leased 1001 {})]
    (is (= :leased (:agent.run/status leased)))
    (is (= 1 (:agent.run/attempt leased)))
    (is (thrown? Exception (model/transition run :succeeded 1002 {})))))

(deftest fold-recovers-legacy-terminal-event-without-weakening-transition
  (let [run (model/agent-run {:goal "legacy consultation"} 1000)
        events [(model/event run :run/submitted 1000 {:run run})
                (model/event run :run/succeeded 1001 {:legacy true})]
        folded (get (model/fold-events events) (:agent.run/id run))]
    (is (= :succeeded (:agent.run/status folded)))
    (is (true? (:agent.run/recovered-lifecycle folded)))))

(deftest duplicate-terminal-events-are-idempotent
  (let [run (assoc (model/agent-run {:goal "stale recovery"} 1000)
                   :agent.run/id "run-duplicate")
        leased (assoc run :agent.run/status :leased)
        running (assoc run :agent.run/status :running)
        failed (model/event running :run/failed 1003
                            {:failure/category :stale-run})
        events [(model/event run :run/submitted 1000 {:run run})
                (model/event run :run/leased 1001 {})
                (model/event leased :run/started 1002 {})
                failed failed]
        folded (get (model/fold-events events) "run-duplicate")]
    (is (= :failed (:agent.run/status folded)))
    (is (= 1003 (:agent.run/updated-at folded)))))

(deftest event-fold-and-resume
  (let [run (assoc (model/agent-run {:goal "fix it"} 1000)
                   :agent.run/id "run-1")
        events [(model/event run :run/submitted 1000 {:run run})
                (model/event run :run/leased 1001 {:agent.run/worker "a"})
                (model/event (assoc run :agent.run/status :leased)
                             :run/started 1002 {})
                (model/event (assoc run :agent.run/status :running)
                             :run/failed 1003 {:agent.run/exit 1})]
        folded (get (model/fold-events events) "run-1")]
    (is (= :failed (:agent.run/status folded)))
    (is (model/resumable? folded))))
