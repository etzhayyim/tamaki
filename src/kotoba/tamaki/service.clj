(ns kotoba.tamaki.service
  "Deterministic service-operation governor.

  It turns measured business, support, and reliability gaps into a local,
  canonical issue topology. It never reads message bodies and never performs
  an external side effect."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files StandardCopyOption]))

(def default-policy
  {:target/untriaged-inbox 0
   :target/support-backlog 5
   :target/awaiting-human 3
   :target/open-incidents 0
   :target/unique-visitors 100
   :target/activation-rate 0.35
   :target/paid-conversion-rate 0.10
   :target/max-churn-rate 0.03})

(def default-lanes
  #{:incident-response :support-triage :support-draft :human-decision
    :acquisition :activation :conversion :retention :outcome-validation})

(defn read-spec [path]
  (let [file (.getCanonicalFile (io/file path))
        spec (edn/read-string (slurp file))
        parent (.getParentFile file)]
    (when-not (map? spec)
      (throw (ex-info "ServiceSpec must be an EDN map" {:path path})))
    (when (or (nil? (:service/id spec))
              (nil? (:service/domain spec))
              (str/blank? (str (:service/project spec)))
              (str/blank? (str (:service/topology-file spec))))
      (throw (ex-info
              "ServiceSpec requires id, domain, project, and topology-file"
              {:path path})))
    (-> spec
        (update :service/policy #(merge default-policy %))
        (update :service/lanes #(set (or % default-lanes)))
        (assoc :service/spec-file (.getCanonicalPath file))
        (update :service/project
                #(.getCanonicalPath (io/file parent %)))
        (update :service/topology-file
                #(.getCanonicalPath (io/file parent %)))
        (cond-> (:service/business-targets spec)
          (update :service/business-targets
                  #(.getCanonicalPath (io/file parent %)))))))

(defn- issue-id [domain lane]
  (str "service/" (name domain) "/" (name lane)))

(defn- node
  [domain lane title active? blockers evidence]
  {:issue/id (issue-id domain lane)
   :issue/title title
   :issue/type :service-operation
   :issue/lane lane
   :issue/status (if active? :open :closed)
   :issue/priority (if active? :p0 :p3)
   :issue/blocked-by (vec blockers)
   :issue/blockers (vec blockers)
   :issue/visibility :local-private
   :issue/projectable? false
   :issue/evidence evidence
   :issue/criteria
   ["The change is represented by source, a reviewed patch, or a redacted response receipt"
    "The next provider observation demonstrates the intended outcome"
    "No credential, message body, recipient address, or private identifier is published"]})

(defn- above? [actual limit]
  (> (double (or actual 0)) (double (or limit 0))))

(defn- below? [actual target]
  (< (double (or actual 0)) (double (or target 0))))

(defn topology
  "Build the complete issue graph. Inactive gaps remain closed so topology
  shape is stable and downstream reverse-topological walks are auditable."
  [spec business-summary now-ms]
  (let [domain (:service/domain spec)
        policy (:service/policy spec)
        lanes (set (or (:service/lanes spec) default-lanes))
        enabled? #(contains? lanes %)
        status (:business/status business-summary)
        observation (:business/observation business-summary)
        stocks (:stocks observation)
        kpis (:business/kpis business-summary)
        unknown? (not= :observed status)
        observe (issue-id domain :observe)
        triage (issue-id domain :support-triage)
        draft (issue-id domain :support-draft)
        acquisition (issue-id domain :acquisition)
        activation (issue-id domain :activation)
        conversion (issue-id domain :conversion)
        incident (issue-id domain :incident-response)
        untriaged? (and (enabled? :support-triage)
                        (above? (:untriaged-inbox stocks)
                                (:target/untriaged-inbox policy)))
        support? (and (enabled? :support-draft)
                      (above? (:support-backlog stocks)
                              (:target/support-backlog policy)))
        awaiting? (and (enabled? :human-decision)
                       (above? (:awaiting-human stocks)
                               (:target/awaiting-human policy)))
        incident? (and (enabled? :incident-response)
                       (above? (:open-incidents stocks)
                               (:target/open-incidents policy)))
        acquisition? (and (enabled? :acquisition)
                          (or unknown?
                              (below? (:unique-visitors kpis)
                                      (:target/unique-visitors policy))))
        activation? (and (enabled? :activation)
                         (or unknown?
                             (below? (:activation-rate kpis)
                                     (:target/activation-rate policy))))
        conversion? (and (enabled? :conversion)
                         (or unknown?
                             (below? (:paid-conversion-rate kpis)
                                     (:target/paid-conversion-rate policy))))
        retention? (and (enabled? :retention)
                        (or unknown?
                            (above? (:churn-rate kpis)
                                    (:target/max-churn-rate policy))))
        validation? (and (enabled? :outcome-validation)
                         (or acquisition? activation? conversion? retention?))
        validation-blockers
        (cond-> []
          acquisition? (conj acquisition)
          activation? (conj activation)
          conversion? (conj conversion)
          retention? (conj (issue-id domain :retention)))]
    {:topology/version 1
     :topology/id (keyword (str "service." (name domain)))
     :topology/domain domain
     :topology/service (:service/id spec)
     :topology/org (:service/org spec)
     :topology/project (:service/project spec)
     :topology/file (:service/topology-file spec)
     :topology/generated-at now-ms
     :topology/authority :local-private-edn
     :topology/walk :reverse-topological
     :topology/issues
     [(node domain :observe "Refresh trustworthy service observations"
            unknown? [] {:business/status status})
      (node domain :incident-response "Restore service and preserve evidence"
            incident? (when unknown? [observe])
            {:open-incidents (:open-incidents stocks 0)})
      (node domain :support-triage "Classify and route the support inbox"
            untriaged? (cond-> [] unknown? (conj observe)
                         incident? (conj incident))
            {:untriaged-inbox (:untriaged-inbox stocks 0)})
      (node domain :support-draft "Draft responses for routed support issues"
            support? (cond-> [] unknown? (conj observe)
                       untriaged? (conj triage))
            {:support-backlog (:support-backlog stocks 0)})
      (node domain :human-decision "Request decisions for held responses"
            awaiting? (cond-> [] unknown? (conj observe)
                        support? (conj draft))
            {:awaiting-human (:awaiting-human stocks 0)
             :effect :approval-required})
      (node domain :acquisition "Improve qualified service discovery"
            acquisition? (when unknown? [observe])
            {:unique-visitors (:unique-visitors kpis 0)})
      (node domain :activation "Improve first-value activation"
            activation? (cond-> [] unknown? (conj observe)
                          acquisition? (conj acquisition))
            {:activation-rate (:activation-rate kpis 0)})
      (node domain :conversion "Improve evidence-backed paid conversion"
            conversion? (cond-> [] unknown? (conj observe)
                          activation? (conj activation))
            {:paid-conversion-rate (:paid-conversion-rate kpis 0)})
      (node domain :retention "Improve retained customer value"
            retention? (cond-> [] unknown? (conj observe)
                         conversion? (conj conversion))
            {:churn-rate (:churn-rate kpis 0)})
      (node domain :outcome-validation "Validate outcomes after 7 and 30 days"
            validation?
            validation-blockers
            {:windows-days [7 30]})]}))

(defn active-walk [topology]
  (let [issues (:topology/issues topology)
        index (into {} (map (juxt :issue/id identity)) issues)
        open? #(= :open (:issue/status (get index %)))]
    (->> issues
         (filter #(= :open (:issue/status %)))
         (mapv (fn [issue]
                 (assoc issue :issue/runnable?
                        (not-any? open? (:issue/blocked-by issue))))))))

(defn write-topology! [path topology]
  (let [target (.getCanonicalFile (io/file path))
        parent (.getParentFile target)
        _ (.mkdirs parent)
        temp (io/file parent (str "." (.getName target) ".next."
                                  (System/nanoTime)))]
    (spit temp (str (pr-str topology) "\n"))
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE]))
    (.getCanonicalPath target)))

(defn event [topology now-ms]
  {:tamaki.event/version 1
   :tamaki.event/id (str (random-uuid))
   :tamaki.event/run (str "service::" (name (:topology/domain topology)))
   :tamaki.event/parent nil
   :tamaki.event/kind :service/reconciled
   :tamaki.event/at now-ms
   :tamaki.event/data
   {:service/id (:topology/service topology)
    :service/domain (:topology/domain topology)
    :service/topology-file (:topology/file topology)
    :service/open-issues
    (count (filter #(= :open (:issue/status %))
                   (:topology/issues topology)))
    :service/runnable
    (mapv :issue/id (filter :issue/runnable? (active-walk topology)))}})
