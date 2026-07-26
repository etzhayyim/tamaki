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
                             1000)]
    (is (= :queued (:agent.run/status run)))
    (is (= "claude-a" (:agent.run/runner run)))
    (is (= "/source" (:agent.run/source-project run)))
    (is (= #{:git} (:agent.run/required-capabilities run)))
    (is (= 12 (get-in run [:agent.run/budget :max-turns])))))

(deftest transition-gate
  (let [run (model/agent-run {:goal "fix it"} 1000)
        leased (model/transition run :leased 1001 {})]
    (is (= :leased (:agent.run/status leased)))
    (is (= 1 (:agent.run/attempt leased)))
    (is (thrown? Exception (model/transition run :succeeded 1002 {})))))

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
