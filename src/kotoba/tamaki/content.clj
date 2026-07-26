(ns kotoba.tamaki.content
  "Result-driven content publication and reaction feedback contracts.

  Creation and analysis are autonomous. Publishing is an external effect and
  remains approval-gated; credentials and provider-specific upload code stay
  outside the public Tamaki repository."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def channels #{:aozora :youtube})
(def stages #{:drafted :rendered :publish-ready :published :observed :learned})

(defn read-spec [path]
  (let [file (.getCanonicalFile (io/file path))
        spec (edn/read-string (slurp file))]
    (assoc spec :content/spec-path (.getPath file))))

(defn validate-spec [spec]
  (when (str/blank? (str (:content/id spec)))
    (throw (ex-info "Content loop requires :content/id" {})))
  (when (str/blank? (:content/project spec))
    (throw (ex-info "Content loop requires :content/project"
                    {:content/id (:content/id spec)})))
  (let [declared (set (keys (:content/channels spec)))]
    (when (empty? declared)
      (throw (ex-info "Content loop requires at least one channel"
                      {:content/id (:content/id spec)})))
    (when-let [unknown (seq (remove channels declared))]
      (throw (ex-info "Unknown content channel"
                      {:content/id (:content/id spec) :channels unknown}))))
  spec)

(defn publication-plan
  "Return a secret-free plan. Approval permits a provider adapter to run, but
  this pure contract never uploads by itself."
  [spec artifact approved?]
  (validate-spec spec)
  (when (str/blank? (:artifact/path artifact))
    (throw (ex-info "Publication requires :artifact/path"
                    {:content/id (:content/id spec)})))
  (let [ready? (= :publish-ready (:artifact/stage artifact))]
    {:content/id (:content/id spec)
     :artifact/id (:artifact/id artifact)
     :artifact/path (:artifact/path artifact)
     :channels (-> spec :content/channels keys set)
     :decision (cond
                 (not ready?) :blocked-not-ready
                 approved? :approved
                 :else :approval-required)
     :external-effect? true
     :executable? (and ready? approved?)}))

(defn- ratio [numerator denominator]
  (if (pos? (double (or denominator 0)))
    (/ (double (or numerator 0)) (double denominator))
    0.0))

(defn reaction-signals
  "Normalize channel facts into comparable, auditable signals. Missing facts
  remain zero; the source snapshot is retained as evidence."
  [{:keys [channel metrics] :as observation}]
  (when-not (contains? channels channel)
    (throw (ex-info "Reaction observation has unknown :channel"
                    {:channel channel})))
  (let [impressions (or (:impressions metrics) (:views metrics) 0)
        engagements (+ (or (:likes metrics) 0)
                       (or (:comments metrics) 0)
                       (or (:replies metrics) 0)
                       (or (:reposts metrics) 0))
        completions (or (:completions metrics) 0)
        watch-seconds (or (:watch-seconds metrics) 0)]
    {:channel channel
     :content/id (:content/id observation)
     :artifact/id (:artifact/id observation)
     :observed-at (:observed-at observation)
     :signals {:reach impressions
               :engagements engagements
               :engagement-rate (ratio engagements impressions)
               :completion-rate (ratio completions impressions)
               :watch-seconds watch-seconds
               :conversions (or (:conversions metrics) 0)
               :revenue-jpy (or (:revenue-jpy metrics) 0)}
     :evidence metrics}))

(defn collect
  "Read a provider-owned, local EDN snapshot through a declarative mapping.
  Absence is reported, never converted into a zero-valued observation."
  [spec]
  (let [source-root (when-let [env-name (:reaction/source-root-env spec)]
                      (System/getenv env-name))
        source (if (and source-root
                        (not (.isAbsolute (io/file (:reaction/source spec)))))
                 (io/file source-root (:reaction/source spec))
                 (io/file (:reaction/source spec)))]
    (if-not (.isFile source)
      {:collector/id (:reaction/id spec)
       :collector/status :unavailable
       :collector/source (.getPath source)}
      (let [snapshot (edn/read-string (slurp source))
            metrics (into {}
                          (map (fn [[metric path]]
                                 [metric (get-in snapshot path)]))
                          (:reaction/mappings spec))]
        {:collector/id (:reaction/id spec)
         :collector/status :observed
         :observation
         {:content/id (:reaction/content-id spec)
          :artifact/id (get-in snapshot
                               (or (:reaction/artifact-id-path spec)
                                   [:artifact/id]))
          :channel (:reaction/channel spec)
          :observed-at (or (get-in snapshot
                                   (or (:reaction/observed-at-path spec)
                                       [:observed-at]))
                           (:as-of snapshot))
          :metrics (into {} (remove (comp nil? val)) metrics)}}))))

(defn next-action
  "Choose the next result-based issue class from observed signals."
  [{:keys [signals] :as normalized}]
  (let [{:keys [reach engagement-rate completion-rate conversions]} signals]
    (assoc normalized :next-action
           (cond
             (zero? reach) :improve-discovery
             (< completion-rate 0.25) :improve-retention
             (< engagement-rate 0.02) :improve-resonance
             (zero? conversions) :improve-call-to-action
             :else :produce-follow-up))))

(defn observation-event [observation now-ms]
  {:tamaki.event/version 1
   :tamaki.event/id (str (random-uuid))
   :tamaki.event/run (str "content::" (name (:content/id observation)))
   :tamaki.event/parent nil
   :tamaki.event/kind :content/reaction-observed
   :tamaki.event/at now-ms
   :tamaki.event/data (next-action (reaction-signals observation))})

(defn status [events content-id]
  (let [observations
        (->> events
             (filter #(= :content/reaction-observed (:tamaki.event/kind %)))
             (map :tamaki.event/data)
             (filter #(= content-id (:content/id %)))
             (sort-by :observed-at)
             vec)]
    {:content/id content-id
     :observations (count observations)
     :latest (last observations)
     :next-action (or (:next-action (last observations))
                      :await-observation)}))
