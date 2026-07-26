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
   (let [normalized (str/replace (str entropy) #"-" "")]
     (when (< (count normalized) 8)
       (throw (ex-info "Run ID entropy must contain at least 8 characters"
                       {:entropy entropy :minimum-length 8})))
     (str "run-" now-ms "-" (subs normalized 0 8)))))

(defn agent-run
  [{:keys [id goal project source-project repo pin mode node model runner
           capabilities budget parent actor replica organism
           require-done-no-edit?]
    :or {mode :local node :auto capabilities #{} budget {}
         require-done-no-edit? false}}
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
   :agent.run/actor actor
   :agent.run/organism organism
   :agent.run/replica replica
   ;; Independent-review (and other observe-only) runs must finish with DONE
   ;; and leave the working tree untouched. Implementation / improvement runs
   ;; must be free to edit; only set this when the role is truly no-edit.
   :agent.run/require-done-no-edit? (boolean require-done-no-edit?)
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
    :run/configured (merge run data)
    :run/leased (transition run :leased at data)
    :run/started (transition run :running at data)
    :run/checkpointed (transition run :checkpointed at data)
    :run/held (transition run :held at data)
    ;; Older supervisor consultation records omitted leased/started. Preserve
    ;; their audit history without allowing callers to bypass `transition`.
    :run/succeeded (if (= :queued (:agent.run/status run))
                     (merge run data
                            {:agent.run/status :succeeded
                             :agent.run/updated-at at
                             :agent.run/recovered-lifecycle true})
                     (transition run :succeeded at data))
    :run/failed (if (= :queued (:agent.run/status run))
                  (merge run data
                         {:agent.run/status :failed
                          :agent.run/updated-at at
                          :agent.run/recovered-lifecycle true})
                  (transition run :failed at data))
    :run/requeued (transition run :queued at data)
    :run/rejected (transition run :rejected at data)
    :run/cancelled (transition run :cancelled at data)
    run))

(defn fold-events
  [events]
  (reduce
   (fn [runs event]
     (let [id (:tamaki.event/run event)
           current (get runs id)
           next-run (apply-event current event)]
       ;; Loop, actor and audit events share the durable event stream but are
       ;; not AgentRuns. Never materialize them as nil run entries.
       (if next-run (assoc runs id next-run) runs)))
   {}
   events))

(defn resumable?
  [run]
  (contains? #{:failed :checkpointed :held} (:agent.run/status run)))
