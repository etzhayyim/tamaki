(ns kotoba.tamaki.loop
  "Pure campaign state for bounded, durable self-improvement cycles."
  (:require [kotoba.tamaki.lineage :as lineage]))

(def terminal-cycle-kinds
  #{:loop/cycle-integrated :loop/cycle-reviewed
    :loop/cycle-no-change :loop/cycle-failed})

(defn campaign-id
  ([now-ms] (campaign-id now-ms (str (random-uuid))))
  ([now-ms entropy]
   (str "loop-" now-ms "-" (subs (clojure.string/replace entropy #"-" "") 0 8))))

(defn campaign
  [{:keys [id objective project model runner runners max-cycles interval-ms
           max-failures auto-approve continuous organism ao spec-id spec-path]
    :or {max-cycles 10 interval-ms 60000 max-failures 3 auto-approve false
         continuous false}}
   now-ms]
  (when (clojure.string/blank? objective)
    (throw (ex-info "Loop requires a non-blank objective" {:field :objective})))
  (when (clojure.string/blank? project)
    (throw (ex-info "Loop requires --project PATH" {:field :project})))
  (when-not (pos? max-cycles)
    (throw (ex-info "Loop requires a positive --max-cycles"
                    {:field :max-cycles :value max-cycles})))
  (when-not (pos? max-failures)
    (throw (ex-info "Loop requires a positive --max-failures"
                    {:field :max-failures :value max-failures})))
  (when (neg? interval-ms)
    (throw (ex-info "Loop requires a non-negative --interval-ms"
                    {:field :interval-ms :value interval-ms})))
  (cond-> {:tamaki.loop/version 1
           :tamaki.loop/id (or id (campaign-id now-ms))
           :tamaki.loop/objective objective
           :tamaki.loop/project project
           :tamaki.loop/model model
           :tamaki.loop/runner runner
           :tamaki.loop/runners (vec (or (seq runners)
                                         (when runner [runner])
                                         []))
           :tamaki.loop/max-cycles max-cycles
           :tamaki.loop/interval-ms interval-ms
           :tamaki.loop/max-failures max-failures
           :tamaki.loop/auto-approve auto-approve
           :tamaki.loop/continuous (boolean continuous)
           :tamaki.loop/organism organism
           :tamaki.loop/expires-at (or (:organism/expires-at organism)
                                       (+ now-ms lineage/default-lifetime-ms))
           :tamaki.loop/status :active
           :tamaki.loop/cycles 0
           :tamaki.loop/failures 0
           :tamaki.loop/created-at now-ms
           :tamaki.loop/updated-at now-ms}
    (not (clojure.string/blank? (str spec-id)))
    (assoc :tamaki.loop/spec-id (str spec-id))
    (not (clojure.string/blank? (str ao)))
    (assoc :tamaki.loop/ao (str ao))
    (not (clojure.string/blank? (str spec-path)))
    (assoc :tamaki.loop/spec-path (str spec-path))))

(defn loop-event [campaign kind now-ms data]
  {:tamaki.event/version 1
   :tamaki.event/id (str (random-uuid))
   :tamaki.event/run (:tamaki.loop/id campaign)
   :tamaki.event/parent nil
   :tamaki.event/kind kind
   :tamaki.event/at now-ms
   :tamaki.event/data data})

(defn apply-event [state event]
  (let [kind (:tamaki.event/kind event)
        data (:tamaki.event/data event)
        at (:tamaki.event/at event)]
    (case kind
      :loop/started (:campaign data)
      :loop/cycle-started (-> state
                              (update :tamaki.loop/cycles inc)
                              (assoc :tamaki.loop/current-cycle
                                     (:loop/cycle data)
                                     :tamaki.loop/updated-at at))
      :loop/cycle-failed (-> state
                             (update :tamaki.loop/failures inc)
                             (dissoc :tamaki.loop/current-cycle)
                             (assoc :tamaki.loop/last-result :failed
                                    :tamaki.loop/last-error (:error data)
                                    :tamaki.loop/updated-at at))
      :loop/cycle-integrated (-> state
                                  (dissoc :tamaki.loop/current-cycle
                                          :tamaki.loop/last-error)
                                  (assoc :tamaki.loop/last-result :integrated
                                         :tamaki.loop/failures 0
                                         :tamaki.loop/updated-at at))
      :loop/cycle-no-change (-> state
                                 (dissoc :tamaki.loop/current-cycle
                                         :tamaki.loop/last-error)
                                 (assoc :tamaki.loop/last-result :no-change
                                        :tamaki.loop/failures 0
                                        :tamaki.loop/updated-at at))
      :loop/cycle-reviewed (-> state
                               (dissoc :tamaki.loop/current-cycle
                                       :tamaki.loop/last-error)
                               (assoc :tamaki.loop/status :paused
                                      :tamaki.loop/last-result :reviewed
                                      :tamaki.loop/updated-at at))
      :loop/paused (assoc state :tamaki.loop/status :paused
                         :tamaki.loop/updated-at at)
      :loop/completed (-> state
                          (dissoc :tamaki.loop/current-cycle)
                          (assoc :tamaki.loop/status :completed
                                 :tamaki.loop/stop-reason (:reason data)
                                 :tamaki.loop/updated-at at))
      state)))

(defn campaigns [events]
  (reduce
   (fn [result event]
     (let [id (:tamaki.event/run event)]
       (if (or (= :loop/started (:tamaki.event/kind event))
               (contains? result id))
         (assoc result id (apply-event (get result id) event))
         result)))
   {} events))

(defn stop-reason
  ([campaign] (stop-reason campaign nil))
  ([campaign now-ms]
   (cond
     (nil? campaign) :unknown-loop
     (not= :active (:tamaki.loop/status campaign)) :not-active
     (and now-ms (:tamaki.loop/expires-at campaign)
          (>= now-ms (:tamaki.loop/expires-at campaign)))
     :organism-lease-expired
     (and (not (:tamaki.loop/continuous campaign))
          (>= (:tamaki.loop/cycles campaign) (:tamaki.loop/max-cycles campaign)))
     :max-cycles
     (>= (:tamaki.loop/failures campaign) (:tamaki.loop/max-failures campaign))
     :max-failures
     :else nil)))

(defn runner-for-cycle
  "Deterministically rotate a persistent campaign through its provider pool."
  [campaign cycle]
  (let [pool (:tamaki.loop/runners campaign)]
    (or (when (seq pool)
          (nth pool (mod (dec cycle) (count pool))))
        (:tamaki.loop/runner campaign))))

(defn cycle-goal [campaign cycle issue-id]
  (str "Autonomous improvement cycle " cycle " for Radicle issue " issue-id
       ". Objective: " (:tamaki.loop/objective campaign) "\n"
       "Inspect the repository, select one small high-value improvement, implement it, "
       "add or improve deterministic tests, and run all documented test suites. "
       "Preserve human agency, relational trust, inheritable learning, future "
       "optionality, and succession integrity. Pause for consent when values "
       "conflict or an action is irreversible. "
       "Keep the change reviewable and do not modify secrets, generated files, "
       "external services, or unrelated repositories. Never acquire resources, "
       "extend the organism lease, or create a successor without explicit approval."))
