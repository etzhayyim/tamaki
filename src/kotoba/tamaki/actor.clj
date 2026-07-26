(ns kotoba.tamaki.actor
  "Pure ActorSpec validation and desired-state reconciliation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kotoba.tamaki.model :as model]))

(def active-statuses #{:queued :leased :running :checkpointed :held})
(def hil-decisions #{:autonomous :voice-required :approval-required :blocked})

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
    (assoc spec :actor/id id :actor/scale scale :actor/runners runners)))

(defn read-spec [path]
  (let [file (io/file path)]
    (when-not (.isFile file)
      (throw (ex-info "ActorSpec file not found" {:path path})))
    (validate-spec (edn/read-string (slurp file)))))

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

(defn effective-desired
  "Baseline :desired raised by live scale-up pressure, clamped to [min, max].

  Declared ActorSpec keys:
  - :scale-up-on {:queue-depth N :blocker-count N}
    When queued or held replicas cross a threshold, raise capacity so HIL-held
    workers do not stall the whole actor and backlog does not sit forever.
  - :scale-down-after-ms (honoured by reconcile-plan cancel selection)"
  [spec runs]
  (let [spec (validate-spec spec)
        scale (:actor/scale spec)
        {:keys [min desired] max-capacity :max} scale
        active (->> (actor-runs spec runs)
                    (filterv #(contains? active-statuses (:agent.run/status %))))
        raised (+ desired
                  (scale-up-extra scale active
                                  (:actor/control-pressure spec)))]
    (-> raised (clojure.core/min max-capacity) (clojure.core/max min))))

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
         active (filterv #(contains? active-statuses (:agent.run/status %)) all)
         desired (effective-desired spec runs)
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
      :cancel (if (neg? delta)
                (mapv :agent.run/id (take (- delta) cancellable))
                [])
      :active active})))

(defn replica-run
  [spec replica-index now-ms]
  (let [pool (runner-pool spec)
        runner (nth pool (mod replica-index (count pool)))]
    (model/agent-run
     {:goal (str "Actor " (:actor/id spec) " replica " replica-index
                 ". Objective: " (:actor/objective spec)
                 "\nProcess the highest-leverage unblocked work item within "
                 "the declared capabilities and governor policy.")
      :project (:actor/project spec)
      :mode :local
      :runner runner
      :model nil
      :capabilities (set (:actor/capabilities spec))
      :parent (str "actor:" (:actor/id spec))
      :actor (:actor/id spec)
      :replica replica-index}
     now-ms)))
