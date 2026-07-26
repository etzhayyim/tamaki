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

(defn reconcile-plan
  [spec runs]
  (let [spec (validate-spec spec)
        all (actor-runs spec runs)
        active (filterv #(contains? active-statuses (:agent.run/status %)) all)
        desired (get-in spec [:actor/scale :desired])
        delta (- desired (count active))]
    {:actor/id (:actor/id spec)
     :desired desired
     :running (count (filter #(contains? #{:leased :running :checkpointed}
                                         (:agent.run/status %)) active))
     :queued (count (filter #(= :queued (:agent.run/status %)) active))
     :spawn (max 0 delta)
     :cancel (if (neg? delta)
               (mapv :agent.run/id
                     (take (- delta)
                           (filter #(contains? #{:queued :held}
                                               (:agent.run/status %))
                                   (reverse active))))
               [])
     :active active}))

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
