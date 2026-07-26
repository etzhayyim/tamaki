(ns kotoba.tamaki.actor
  "Pure ActorSpec validation and desired-state reconciliation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kotoba.tamaki.model :as model]
            [kotoba.tamaki.visibility :as visibility]))

(def active-statuses #{:queued :leased :running :checkpointed :held})
(def hil-decisions #{:autonomous :voice-required :approval-required :blocked})
(def default-lease-grace-ms 120000)
(def integrated-issue-statuses #{:integrated :closed})

(defn actor-id [value]
  (cond
    (keyword? value) value
    (and (string? value) (not (str/blank? value))) (keyword value)
    :else (throw (ex-info "ActorSpec requires :actor/id" {:value value}))))

(defn validate-spec
  [spec]
  (let [id (actor-id (:actor/id spec))
        project (:actor/project spec)
        objective (:actor/objective spec)
        scale (merge {:min 0 :desired 1 :max 1} (:actor/scale spec))
        {:keys [min desired max]} scale
        runners (vec (:actor/runners spec))
        policy (:actor/hil-policy spec)]
    (when (or (str/blank? project) (str/blank? (str objective)))
      (throw (ex-info "ActorSpec requires project and objective"
                      {:actor/id id})))
    (when-not (and (integer? min) (integer? desired) (integer? max)
                   (<= 0 min desired max) (pos? max))
      (throw (ex-info "ActorSpec scale must satisfy 0 <= min <= desired <= max"
                      {:actor/id id :actor/scale scale})))
    (when (empty? runners)
      (throw (ex-info "ActorSpec requires at least one runner"
                      {:actor/id id})))
    (doseq [{:keys [runner weight]} runners]
      (when (or (str/blank? (name runner)) (not (pos-int? (or weight 1))))
        (throw (ex-info "Actor runner requires an id and positive weight"
                        {:actor/id id :runner runner :weight weight}))))
    (doseq [[gate decision] policy]
      (when-not (contains? hil-decisions decision)
        (throw (ex-info "Unknown ActorSpec HIL policy"
                        {:actor/id id :gate gate :decision decision}))))
    (visibility/validate-actor
     (assoc spec :actor/id id :actor/scale scale :actor/runners runners))))

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
           "\nRunnable issues: "
           (if (seq runnable)
             (str/join ", "
                       (map (fn [issue]
                              (str (:issue/id issue) " — "
                                   (:issue/title issue)))
                            runnable))
             "none")
           ". Work only on a listed runnable issue. Update its EDN state "
           "only when source, tests, review, or integration evidence exists; "
           "then reconcile the projection to the declared issue authority."))))

(defn runner-pool [spec]
  (let [runners (:actor/runners spec)
        max-weight (apply max (map #(or (:weight %) 1) runners))]
    ;; Spread the first replicas across providers before consuming additional
    ;; weight, so desired=2 never means two copies of the same account merely
    ;; because that provider has the highest weight.
    (->> (range max-weight)
         (mapcat (fn [round]
                   (keep (fn [{:keys [runner weight]}]
                           (when (< round (or weight 1)) (name runner)))
                         runners)))
         vec)))

(defn actor-runs [spec runs]
  (let [id (:actor/id spec)]
    (->> runs
         (filter #(= id (:agent.run/actor %)))
         (sort-by :agent.run/created-at)
         vec)))

(defn- scale-up-extra
  "Extra replicas warranted by declared :scale-up-on pressure."
  [scale active control-pressure]
  (let [{:keys [queue-depth blocker-count]} (:scale-up-on scale)
        queued (count (filter #(= :queued (:agent.run/status %)) active))
        blocked (count (filter #(= :held (:agent.run/status %)) active))
        business-threshold (get-in scale [:scale-up-on :business-pressure])]
    (cond-> 0
      (and queue-depth (integer? queue-depth) (pos? queue-depth)
           (>= queued queue-depth))
      (+ (max 1 (inc (- queued queue-depth))))
      (and blocker-count (integer? blocker-count) (pos? blocker-count)
           (>= blocked blocker-count))
      (+ blocked)
      (and (number? business-threshold)
           (>= (double (or control-pressure 0.0))
               (double business-threshold)))
      (+ (max 1 (long (Math/ceil
                       (* 2.0 (double (or control-pressure 0.0))))))))))

(defn stale-run?
  "An active durable state without a heartbeat past its execution deadline is
  a ghost. Activity currently does not fold into AgentRun state, so the run
  deadline plus a bounded grace period is the conservative lease."
  [run now-ms]
  (when (and now-ms (contains? active-statuses (:agent.run/status run)))
    (let [updated (or (:agent.run/updated-at run)
                      (:agent.run/created-at run) 0)
          deadline (or (get-in run [:agent.run/budget :deadline-ms]) 1200000)]
      (>= (- now-ms updated) (+ deadline default-lease-grace-ms)))))

(defn- live-active-runs [spec runs now-ms]
  (->> (actor-runs spec runs)
       (filter #(contains? active-statuses (:agent.run/status %)))
       (remove #(stale-run? % now-ms))
       vec))

(defn effective-desired
  "Baseline :desired raised by live scale-up pressure, clamped to [min, max].

  Declared ActorSpec keys:
  - :scale-up-on {:queue-depth N :blocker-count N}
    When queued or held replicas cross a threshold, raise capacity so HIL-held
    workers do not stall the whole actor and backlog does not sit forever.
  - :scale-down-after-ms (honoured by reconcile-plan cancel selection)"
  ([spec runs] (effective-desired spec runs nil))
  ([spec runs now-ms]
   (let [spec (validate-spec spec)
         scale (:actor/scale spec)
         {:keys [min desired] max-capacity :max} scale
         active (live-active-runs spec runs now-ms)
         raised (+ desired
                   (scale-up-extra scale active
                                   (:actor/control-pressure spec)))]
     (-> raised (clojure.core/min max-capacity) (clojure.core/max min)))))

(defn- cancel-candidates
  [active now-ms scale-down-after-ms]
  (->> active
       reverse
       (filter #(contains? #{:queued :held} (:agent.run/status %)))
       (filter (fn [run]
                 (or (nil? scale-down-after-ms)
                     (nil? now-ms)
                     (let [updated (or (:agent.run/updated-at run)
                                       (:agent.run/created-at run)
                                       0)]
                       (>= (- now-ms updated) scale-down-after-ms)))))
       vec))

(defn reconcile-plan
  "Plan spawn/cancel actions to reach effective desired capacity.

  Optional now-ms enables :scale-down-after-ms so excess replicas are only
  cancelled after they have been idle (queued/held) long enough."
  ([spec runs] (reconcile-plan spec runs nil))
  ([spec runs now-ms]
   (let [spec (validate-spec spec)
         scale (:actor/scale spec)
         all (actor-runs spec runs)
         stale (filterv #(stale-run? % now-ms) all)
         active (live-active-runs spec runs now-ms)
         desired (effective-desired spec runs now-ms)
         delta (- desired (count active))
         cancellable (cancel-candidates active now-ms
                                        (:scale-down-after-ms scale))]
     {:actor/id (:actor/id spec)
      :desired desired
      :running (count (filter #(contains? #{:leased :running :checkpointed}
                                          (:agent.run/status %)) active))
      :queued (count (filter #(= :queued (:agent.run/status %)) active))
      :blocked (count (filter #(= :held (:agent.run/status %)) active))
      :spawn (max 0 delta)
      :reap (mapv :agent.run/id stale)
      :cancel (if (neg? delta)
                (mapv :agent.run/id (take (- delta) cancellable))
                [])
      :active active})))

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
      :require-done-no-edit? observe-only?
      :parent (str "actor:" (:actor/id spec))
      :actor (:actor/id spec)
      :organism (:actor/organism spec)
      :replica replica-index}
     now-ms)))
