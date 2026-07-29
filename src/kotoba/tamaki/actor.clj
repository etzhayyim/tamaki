(ns kotoba.tamaki.actor
  "Pure ActorSpec validation and desired-state reconciliation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kotoba.tamaki.capability :as capability]
            [kotoba.tamaki.model :as model]
            [kotoba.tamaki.visibility :as visibility]
            [yakuwari.policy :as yakuwari-policy]
            [yakuwari.reconcile :as yakuwari-reconcile]
            [yakuwari.spec :as yakuwari-spec]))

(def active-statuses yakuwari-reconcile/active-statuses)
(def hil-decisions yakuwari-policy/decision-set)
(def default-lease-grace-ms yakuwari-reconcile/default-lease-grace-ms)
(def integrated-issue-statuses #{:integrated :closed})

(defn actor-id [value]
  (try
    (yakuwari-spec/yakuwari-id value)
    (catch Exception _
      (throw (ex-info "ActorSpec requires :actor/id" {:value value})))))

(defn- as-yakuwari
  "Translate Tamaki's persisted ActorSpec spelling into the shared durable
  role contract. Attribute names remain stable at the adapter boundary."
  [spec]
  {:yakuwari/id (actor-id (:actor/id spec))
   :yakuwari/project (:actor/project spec)
   :yakuwari/objective (:actor/objective spec)
   :yakuwari/scale (:actor/scale spec)
   :yakuwari/runners (:actor/runners spec)
   :yakuwari/policy (:actor/hil-policy spec)
   :yakuwari/control-pressure (:actor/control-pressure spec)
   :yakuwari/run-key :agent.run/actor
   :yakuwari/stale-policy :execution-deadline
   :yakuwari/pressure-mode :tamaki
   :yakuwari/lease-grace-ms default-lease-grace-ms})

(defn validate-spec
  [spec]
  (let [role (yakuwari-spec/validate! (as-yakuwari spec))
        normalized (assoc spec
                          :actor/id (:yakuwari/id role)
                          :actor/scale (:yakuwari/scale role)
                          :actor/runners (:yakuwari/runners role))]
    (when-let [execution (:actor/execution spec)]
      (capability/validate! (:actor/capabilities spec) execution))
    (visibility/validate-actor normalized)))

(defn read-spec [path]
  (let [file (io/file path)]
    (when-not (.isFile file)
      (throw (ex-info "ActorSpec file not found" {:path path})))
    (let [spec (edn/read-string (slurp file))
          project (:actor/project spec)
          targets (:actor/business-targets spec)
          topology (:actor/issue-topology-file spec)
          parent (.getParentFile (.getCanonicalFile file))]
      ;; Actor specs are often portable and use ".". Resolve it at the
      ;; operator boundary so worktree naming never creates a child checkout
      ;; inside the canonical repository.
      (validate-spec
       (cond-> spec
         project (assoc :actor/project
                        (.getCanonicalPath (io/file project)))
         targets (assoc :actor/business-targets
                        (.getCanonicalPath
                         (io/file parent targets)))
         topology (assoc :actor/issue-topology-file
                         (.getCanonicalPath
                          (io/file parent topology))))))))

(defn read-issue-topology
  "Read the repo-owned canonical issue topology referenced by an ActorSpec."
  [spec]
  (when-let [path (:actor/issue-topology-file spec)]
    (let [file (io/file path)]
      (when-not (.isFile file)
        (throw (ex-info "Canonical issue topology file not found"
                        {:actor/id (:actor/id spec) :path path})))
      (edn/read-string (slurp file)))))

(defn runnable-issues
  "Select open issues whose blocker entities are integrated or closed.
  Unknown blockers fail closed."
  [topology]
  (let [issues (:topology/issues topology)
        index (into {} (map (juxt :issue/id identity)) issues)
        done? (fn [id]
                (contains? integrated-issue-statuses
                           (:issue/status (get index id))))]
    (->> issues
         (filter #(= :open (:issue/status %)))
         (filter #(every? done? (:issue/blocked-by %)))
         (sort-by (juxt :issue/priority :issue/id))
         vec)))

(defn- topology-prompt [spec]
  (when-let [topology (read-issue-topology spec)]
    (let [runnable (runnable-issues topology)]
      (str "\nCanonical issue topology (EDN): "
           (:actor/issue-topology-file spec)
           ". Topology authority: EDN; forge issues are projections."
           " The path is supervisor metadata outside the isolated worktree: "
           "do not read, write, or otherwise access it during this run. "
           "The embedded runnable snapshot below is the complete topology "
           "input authorized for this run."
           "\nRunnable issue snapshot: "
           (if (seq runnable)
             (pr-str
              (mapv #(select-keys
                      % [:issue/id :issue/title :issue/type :issue/lane
                         :issue/priority :issue/blocked-by :issue/evidence
                         :issue/criteria])
                    runnable))
             "none")
           ". Work only on a listed runnable issue. Produce source, tests, "
           "review evidence, a redacted receipt, or an explicit blocked "
           "result. If the evidence shows that a credential, human action, "
           "or unavailable external authority is required, return the "
           "blocked result immediately instead of exploring unrelated code. "
           "The supervisor alone updates canonical EDN and reconciles "
           "its forge projection after validating that evidence."))))

(defn runner-pool [spec]
  (yakuwari-spec/runner-pool (as-yakuwari spec)))

(defn actor-runs [spec runs]
  (->> (yakuwari-reconcile/runs-for (as-yakuwari spec) runs)
       (sort-by :agent.run/created-at)
       vec))

(defn stale-run?
  "An active durable state without a heartbeat past its execution deadline is
  a ghost. Activity currently does not fold into AgentRun state, so the run
  deadline plus a bounded grace period is the conservative lease."
  [run now-ms]
  (yakuwari-reconcile/stale-run?
   {:yakuwari/stale-policy :execution-deadline
    :yakuwari/lease-grace-ms default-lease-grace-ms}
   run now-ms))

(defn effective-desired
  "Baseline :desired raised by live scale-up pressure, clamped to [min, max].

  Declared ActorSpec keys:
  - :scale-up-on {:queue-depth N :blocker-count N}
    When queued or held replicas cross a threshold, raise capacity so HIL-held
    workers do not stall the whole actor and backlog does not sit forever.
  - :scale-down-after-ms (honoured by reconcile-plan cancel selection)"
  ([spec runs] (effective-desired spec runs nil))
  ([spec runs now-ms]
   (yakuwari-reconcile/effective-desired
    (as-yakuwari (validate-spec spec)) runs now-ms)))

(defn reconcile-plan
  "Plan spawn/cancel actions to reach effective desired capacity.

  Optional now-ms enables :scale-down-after-ms so excess replicas are only
  cancelled after they have been idle (queued/held) long enough."
  ([spec runs] (reconcile-plan spec runs nil))
  ([spec runs now-ms]
   (let [plan (yakuwari-reconcile/plan
               (as-yakuwari (validate-spec spec)) runs now-ms)]
     (-> plan
         (dissoc :yakuwari/id)
         (assoc :actor/id (:yakuwari/id plan))))))

(defn replica-run
  [spec replica-index now-ms]
  (let [pool (runner-pool spec)
        runner (nth pool (mod replica-index (count pool)))
        capabilities (set (:actor/capabilities spec))
        observe-only? (and (contains? capabilities :loop-evaluation)
                           (not (contains? capabilities :implementation)))]
    (model/agent-run
     {:goal (str "Actor " (:actor/id spec) " replica " replica-index
                 ". Objective: " (:actor/objective spec)
                 "\nRepository visibility: "
                 (name (or (:actor/repository-visibility spec) :unspecified))
                 ". Issue and delivery authority: "
                 (name (or (:actor/issue-authority spec) :unspecified))
                 ". Never publish through a different authority."
                 (or (topology-prompt spec) "")
                 "\nProcess the highest-leverage unblocked work item within "
                 "the declared capabilities and governor policy.")
      :project (:actor/project spec)
      :mode :local
      :runner runner
      :model nil
      :capabilities capabilities
      :budget (:actor/budget spec)
      :require-done-no-edit? observe-only?
      :parent (str "actor:" (:actor/id spec))
      :actor (:actor/id spec)
      :organism (:actor/organism spec)
      :replica replica-index}
     now-ms)))
