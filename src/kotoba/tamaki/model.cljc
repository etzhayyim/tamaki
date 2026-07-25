(ns kotoba.tamaki.model
  "Portable AgentRun contract and pure state transitions."
  (:require [clojure.string :as str]))

(def contract-version 1)

(def terminal-statuses
  #{:succeeded :failed :rejected :cancelled})

(def transitions
  {:queued #{:leased :cancelled}
   :leased #{:running :queued :failed :cancelled}
   :running #{:checkpointed :held :succeeded :failed :cancelled}
   :checkpointed #{:leased :running :held :succeeded :failed :cancelled}
   :held #{:leased :running :rejected :cancelled}
   :succeeded #{}
   :failed #{:queued}
   :rejected #{}
   :cancelled #{}})

(defn run-id
  ([now-ms] (run-id now-ms (str (random-uuid))))
  ([now-ms entropy]
   (str "run-" now-ms "-" (subs (str/replace entropy #"-" "") 0 8))))

(defn agent-run
  [{:keys [id goal project source-project repo pin mode node model runner capabilities budget parent]
    :or {mode :local node :auto capabilities #{} budget {}}}
   now-ms]
  (when (str/blank? goal)
    (throw (ex-info "AgentRun requires a non-blank goal" {:field :goal})))
  {:agent.run/version contract-version
   :agent.run/id (or id (run-id now-ms))
   :agent.run/goal goal
   :agent.run/project project
   :agent.run/source-project source-project
   :agent.run/repo repo
   :agent.run/pin pin
   :agent.run/mode mode
   :agent.run/node node
   :agent.run/model model
   :agent.run/runner runner
   :agent.run/required-capabilities (set capabilities)
   :agent.run/budget (merge {:max-turns 12
                             :max-tool-calls 30
                             :max-tokens 4000
                             :deadline-ms 1200000
                             :test-timeout-ms 180000}
                            budget)
   :agent.run/parent parent
   :agent.run/status :queued
   :agent.run/created-at now-ms
   :agent.run/updated-at now-ms
   :agent.run/attempt 0
   :agent.run/artifacts []})

(defn transition
  [run status now-ms attrs]
  (let [from (:agent.run/status run)]
    (when-not (contains? (get transitions from #{}) status)
      (throw (ex-info "Invalid AgentRun transition"
                      {:run-id (:agent.run/id run) :from from :to status})))
    (cond-> (merge run attrs
                   {:agent.run/status status
                    :agent.run/updated-at now-ms})
      (= status :leased) (update :agent.run/attempt inc))))

(defn event
  [run kind now-ms data]
  {:tamaki.event/version contract-version
   :tamaki.event/id (str (random-uuid))
   :tamaki.event/run (:agent.run/id run)
   :tamaki.event/parent (:agent.run/parent run)
   :tamaki.event/kind kind
   :tamaki.event/at now-ms
   :tamaki.event/data data})

(defn apply-event
  [run {:tamaki.event/keys [kind at data]}]
  (case kind
    :run/submitted (or run (:run data))
    :run/leased (transition run :leased at data)
    :run/started (transition run :running at data)
    :run/checkpointed (transition run :checkpointed at data)
    :run/held (transition run :held at data)
    :run/succeeded (transition run :succeeded at data)
    :run/failed (transition run :failed at data)
    :run/requeued (transition run :queued at data)
    :run/rejected (transition run :rejected at data)
    :run/cancelled (transition run :cancelled at data)
    run))

(defn fold-events
  [events]
  (reduce
   (fn [runs event]
     (let [id (:tamaki.event/run event)
           current (get runs id)]
       (assoc runs id (apply-event current event))))
   {}
   events))

(defn resumable?
  [run]
  (contains? #{:failed :checkpointed :held} (:agent.run/status run)))
