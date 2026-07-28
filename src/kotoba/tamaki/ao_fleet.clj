(ns kotoba.tamaki.ao-fleet
  "Bounded activation and scheduling for repository-bound Etzhayyim AOs.

  The family registry says what exists. This controller decides which AOs
  deserve scarce agent capacity now; it never grants cross-repository
  authority or bypasses review/integration gates."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time Duration Instant]))

(def fleet-version 1)

(defn read-policy [path]
  (edn/read-string (slurp (io/file path))))

(defn validate-policy! [policy]
  (let [required [:ao.fleet/family :ao.fleet/selection
                  :ao.fleet/runners :ao.fleet/authority]
        missing (filterv #(nil? (get policy %)) required)
        selection (:ao.fleet/selection policy)
        max-active (:max-active selection)
        dispatch (:dispatch-per-reconcile selection)]
    (when (seq missing)
      (throw (ex-info "AO fleet policy is incomplete" {:missing missing})))
    (when-not (and (pos-int? max-active)
                   (pos-int? dispatch)
                   (<= dispatch max-active))
      (throw (ex-info "AO fleet selection bounds are invalid"
                      {:selection selection})))
    (when (empty? (:ao.fleet/runners policy))
      (throw (ex-info "AO fleet requires at least one runner" {})))
    (when-not (= :approval-required
                 (get-in policy [:ao.fleet/authority :integrate]))
      (throw (ex-info "AO fleet integration must remain approval-required"
                      {:authority (:ao.fleet/authority policy)})))
    policy))

(defn- instant-ms [value]
  (when (and (string? value) (not (str/blank? value)))
    (try (.toEpochMilli (Instant/parse value))
         (catch Exception _ nil))))

(defn- freshness-score [pushed-at now-ms]
  (if-let [at (instant-ms pushed-at)]
    (let [days (/ (double (max 0 (- now-ms at))) 86400000.0)]
      (cond
        (< days 2) 1.0
        (< days 7) 0.75
        (< days 30) 0.4
        :else 0.1))
    0.0))

(defn repository-path [workspace ao]
  (io/file workspace "orgs" "etzhayyim"
           (get-in ao [:ao/repository :name]
                   (:ao/given-name ao))))

(defn- git-config [repo]
  (let [dotgit (io/file repo ".git")]
    (cond
      (.isDirectory dotgit) (io/file dotgit "config")
      (.isFile dotgit)
      (let [line (str/trim (slurp dotgit))
            relative (second (re-matches #"gitdir:\s*(.+)" line))]
        (when relative
          (io/file (.getParentFile dotgit) relative "config")))
      :else nil)))

(defn west-radicle-identities [workspace]
  (let [file (io/file workspace "manifest" "west.yml")]
    (if-not (.isFile file)
      {}
      (loop [lines (str/split-lines (slurp file))
             path nil
             result {}]
        (if-let [line (first lines)]
          (if-let [value (second (re-find #"^\s*path:\s*(\S+)" line))]
            (recur (rest lines) value result)
            (if-let [rid (second (re-find #"^\s*rad-rid:\s*(\S+)" line))]
              (recur (rest lines) path
                     (if path (assoc result path rid) result))
              (recur (rest lines) path result)))
          result)))))

(defn local-observation
  ([workspace ao]
   (local-observation workspace ao (west-radicle-identities workspace)))
  ([workspace ao radicle-identities]
  (let [repo (repository-path workspace ao)
        config (git-config repo)
        text (when (and config (.isFile config)) (slurp config))
        relative (str "orgs/etzhayyim/" (:ao/given-name ao))
        rid (get radicle-identities relative)]
    {:local/path (.getAbsolutePath repo)
     :local/checkout? (boolean (and (.isDirectory repo) config))
     :local/radicle-configured?
     (boolean (and text
                   (re-find #"(?m)^\s*\[remote \"rad\"\]\s*$" text)))
     :local/radicle-id rid
     :local/radicle?
     (boolean (or rid
                  (and text
                       (re-find #"(?m)^\s*\[remote \"rad\"\]\s*$"
                                text))))})))

(defn activation-score [policy ao observation now-ms]
  (let [weights (merge {:open-issue 3.0 :open-pr 2.0
                        :freshness 1.0 :representative 0.1}
                       (:ao.fleet/weights policy))
        issues (long (or (get-in ao [:ao/signals :open-issues]) 0))
        prs (long (or (get-in ao [:ao/signals :open-pull-requests]) 0))
        freshness (freshness-score
                   (get-in ao [:ao/repository :pushed-at]) now-ms)
        eligible? (and (= :active (:ao/status ao))
                       (:local/radicle? observation)
                       (not (get-in ao [:ao/repository :fork?])))
        score (if eligible?
                (+ (* (min issues 10) (:open-issue weights))
                   (* (min prs 10) (:open-pr weights))
                   (* freshness (:freshness weights))
                   (if (:ao/representative? ao)
                     (:representative weights) 0.0))
                0.0)]
    {:ao/id (:ao/id ao)
     :ao/repository (get-in ao [:ao/repository :slug])
     :ao/project (:local/path observation)
     :ao/eligible? eligible?
     :ao/score score
     :ao/signals {:open-issues issues
                  :open-pull-requests prs
                  :freshness freshness
                  :local-checkout (:local/checkout? observation)
                  :radicle-ready (:local/radicle? observation)
                  :radicle-configured
                  (:local/radicle-configured? observation)
                  :radicle-id (:local/radicle-id observation)}
     :ao/exclusions
     (cond-> []
       (not= :active (:ao/status ao)) (conj :dormant)
       (not (:local/radicle? observation)) (conj :radicle-unavailable)
       (get-in ao [:ao/repository :fork?]) (conj :fork))}))

(defn projection [policy registry workspace previous now-ms]
  (validate-policy! policy)
  (when-not (= (:ao.fleet/family policy) (:family/id registry))
    (throw (ex-info "AO fleet policy and family registry do not match"
                    {:policy-family (:ao.fleet/family policy)
                     :registry-family (:family/id registry)})))
  (let [radicle-identities (west-radicle-identities workspace)
        scores (->> (:family/organisms registry)
                    (map (fn [ao]
                           (activation-score
                            policy ao
                            (local-observation workspace ao
                                               radicle-identities)
                            now-ms)))
                    (sort-by (juxt (comp - :ao/score) :ao/id))
                    vec)
        selection (:ao.fleet/selection policy)
        minimum (double (or (:min-score selection) 0.0))
        last-dispatched (or (:ao.fleet/last-dispatched-at previous) {})
        selected (->> scores
                      (filter :ao/eligible?)
                      (filter #(>= (:ao/score %) minimum))
                      ;; Preserve leverage ordering, but rotate equal-scoring
                      ;; AOs by oldest dispatch so an alphabetical prefix
                      ;; cannot monopolize the family forever.
                      (sort-by (juxt (comp - :ao/score)
                                     #(get last-dispatched (:ao/id %) 0)
                                     :ao/id))
                      (take (:max-active selection))
                      vec)
        dispatch (->> selected
                      (sort-by (juxt #(get last-dispatched (:ao/id %) 0)
                                     (comp - :ao/score)
                                     :ao/id))
                      (take (:dispatch-per-reconcile selection))
                      vec)]
    {:ao.fleet/version fleet-version
     :ao.fleet/family (:family/id registry)
     :ao.fleet/observed-at now-ms
     :ao.fleet/policy
     (select-keys policy [:ao.fleet/selection :ao.fleet/authority])
     :ao.fleet/summary
     {:family-total (count scores)
      :eligible (count (filter :ao/eligible? scores))
      :selected (count selected)
      :dispatch (count dispatch)
      :excluded (- (count scores) (count (filter :ao/eligible? scores)))}
     :ao.fleet/selected selected
     :ao.fleet/dispatch dispatch
     :ao.fleet/scores scores
     :ao.fleet/last-dispatched-at last-dispatched}))

(defn loop-spec [policy candidate]
  (let [repo (last (str/split (:ao/repository candidate) #"/"))]
    {:loop/id (keyword "ao" repo)
     :loop/ao-id (:ao/id candidate)
     :loop/enabled true
     :loop/description
     "Repository-bound AO loop generated into local runtime state."
     :loop/tags #{:etzhayyim :tamaki-family :repository-ao :radicle-first}
     :loop/objective
     (str "Act as " (:ao/id candidate) " for repository "
          (:ao/repository candidate) ". Observe Radicle issues first, select "
          "the highest-leverage unblocked issue, produce a focused source and "
          "test patch, obtain independent review, and leave integration at "
          "the human approval boundary. Judge outputs and repository evidence; "
          "do not infer authority from another AO or publish private data.")
     :loop/project (:ao/project candidate)
     :loop/continuous true
     :loop/interval-ms
     (get-in policy [:ao.fleet/selection :revisit-after-ms] 3600000)
     :loop/max-cycles 10
     :loop/max-failures 3
     :loop/auto-approve false
     :loop/runners (mapv (fn [runner]
                           (if (keyword? runner) (name runner) (str runner)))
                         (:ao.fleet/runners policy))}))

(defn state-file [root]
  (io/file root "families" "etzhayyim-fleet.edn"))

(defn loop-dir [root]
  (io/file root "families" "loops"))

(defn read-state [root]
  (let [file (state-file root)]
    (when (.isFile file) (edn/read-string (slurp file)))))

(defn- atomic-write! [file value]
  (.mkdirs (.getParentFile file))
  (let [temp (io/file (.getParentFile file)
                      (str "." (.getName file) "." (random-uuid) ".tmp"))]
    (spit temp (str (pr-str value) "\n"))
    (when-not (.renameTo temp file)
      (io/copy temp file)
      (.delete temp)))
  value)

(defn write-state! [root state]
  (atomic-write! (state-file root) state))

(defn write-loop-specs! [root policy candidates]
  (mapv
   (fn [candidate]
     (let [repo (last (str/split (:ao/repository candidate) #"/"))
           file (io/file (loop-dir root) (str repo ".edn"))]
       (atomic-write! file (loop-spec policy candidate))
       (.getAbsolutePath file)))
   candidates))

(defn public-summary [state]
  (select-keys state
               [:ao.fleet/version :ao.fleet/family
                :ao.fleet/observed-at :ao.fleet/summary
                :ao.fleet/selected :ao.fleet/dispatch
                :ao.fleet/results]))
