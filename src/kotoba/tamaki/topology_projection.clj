(ns kotoba.tamaki.topology-projection
  "Import forge issues into canonical EDN, then project EDN to Radicle."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [clojure.string :as str]
            [kotoba.tamaki.delivery :as delivery]))

(def issue-type "xyz.radicle.issue")
(def managed-label-prefixes ["program:" "layer:" "priority:"])
(def managed-labels #{"blocked"})

(defn read-topology [path]
  (edn/read-string (slurp (io/file path))))

(defn- parse-json [value]
  (json/parse-string (or value "{}") true))

(defn- split-labels [labels]
  (->> labels
       (mapcat #(str/split (str %) #","))
       (map str/trim)
       (remove str/blank?)
       set))

(defn- first-comment-body [issue]
  (let [comments (get-in issue [:thread :comments])
        timeline (get-in issue [:thread :timeline])]
    (or (some #(get-in comments [% :body]) timeline)
        (some-> comments vals first :body)
        "")))

(defn radicle-issue [id raw]
  {:forge :radicle
   :forge/id id
   :title (:title raw)
   :description (first-comment-body raw)
   :status (keyword (get-in raw [:state :status] "open"))
   :labels (split-labels (:labels raw))})

(defn github-issue [repo raw]
  {:forge :github
   :forge/id (:number raw)
   :repo repo
   :title (:title raw)
   :description (or (:body raw) "")
   :status (if (= "CLOSED" (:state raw)) :closed :open)
   :labels (set (map :name (:labels raw)))
   :url (:url raw)})

(defn fetch-radicle [rid project]
  (let [listed (delivery/succeeded!
                (delivery/execute!
                 ["rad" "cob" "list" "--repo" rid "--type" issue-type]
                 project)
                "Radicle issue list")
        ids (remove str/blank? (str/split-lines (:out listed)))]
    (mapv
     (fn [id]
       (let [shown (delivery/succeeded!
                    (delivery/execute!
                     ["rad" "cob" "show" "--repo" rid "--type" issue-type
                      "--object" id "--format" "json"]
                     project)
                    "Radicle issue show")]
         (radicle-issue id (parse-json (:out shown)))))
     ids)))

(defn fetch-github [repo project]
  (if (str/blank? repo)
    []
    (let [result
          (delivery/succeeded!
           (delivery/execute!
            ["gh" "issue" "list" "--repo" repo "--state" "all"
             "--limit" "1000" "--json"
             "number,title,body,state,labels,url,createdAt,updatedAt,closedAt"]
            project)
           "GitHub issue list")]
      (mapv #(github-issue repo %) (parse-json (:out result))))))

(defn- projection-id [issue forge]
  (get-in issue [:issue/projections forge :id]))

(defn- match-issue [issues {:keys [forge forge/id title]}]
  (or (some #(when (= id (projection-id % forge)) %) issues)
      (when (= forge :radicle)
        (some #(when (= id (:issue/id %)) %) issues))
      (some #(when (= title (:issue/title %)) %) issues)))

(defn- canonical-id [{:keys [forge forge/id]}]
  (case forge
    :radicle id
    :github (str "github:" id)
    (str (name forge) ":" id)))

(defn merge-import
  "Merge observations without changing canonical blockers, project or outcomes."
  [topology observations]
  (reduce
   (fn [current observation]
     (let [issues (:topology/issues current)
           matched (match-issue issues observation)
           id (or (:issue/id matched) (canonical-id observation))
           projection {:id (:forge/id observation)
                       :status (:status observation)
                       :labels (vec (sort (:labels observation)))}
           projection (cond-> projection
                        (:repo observation) (assoc :repo (:repo observation))
                        (:url observation) (assoc :url (:url observation)))
           imported {:issue/id id
                     :issue/title (:title observation)
                     :issue/description (:description observation)
                     :issue/status (:status observation)
                     :issue/priority :p2
                     :issue/blocked-by []
                     :issue/projections {(:forge observation) projection}}
           updated (if matched
                     (mapv (fn [issue]
                             (if (= id (:issue/id issue))
                               (-> issue
                                   (assoc :issue/title (:title observation)
                                          :issue/description
                                          (:description observation))
                                   (assoc-in [:issue/projections
                                              (:forge observation)]
                                             projection))
                               issue))
                           issues)
                     (conj (vec issues) imported))]
       (assoc current :topology/issues updated)))
   topology observations))

(defn import-plan [topology radicle github]
  (let [before-issues (:topology/issues topology)
        ;; Radicle is the declared issue authority for public topologies, so
        ;; its observation wins when both forges describe the same title.
        merged (merge-import topology (concat github radicle))
        after-issues (:topology/issues merged)
        before-by-id (into {} (map (juxt :issue/id identity)) before-issues)
        after-by-id (into {} (map (juxt :issue/id identity)) after-issues)
        created-ids (set/difference (set (keys after-by-id))
                                    (set (keys before-by-id)))
        updated-ids (->> (set/intersection (set (keys before-by-id))
                                           (set (keys after-by-id)))
                         (filter #(not= (get before-by-id %)
                                        (get after-by-id %)))
                         set)]
    {:topology merged
     :import/radicle (count radicle)
     :import/github (count github)
     :import/created (count created-ids)
     :import/updated (count updated-ids)}))

(defn write-topology! [path topology]
  (with-open [writer (io/writer (io/file path))]
    (binding [*out* writer
              *print-namespace-maps* false]
      (pprint/pprint topology)))
  path)

(defn- desired-radicle-status [status]
  (case status
    :integrated :solved
    :closed :closed
    :open))

(defn- managed-label? [label]
  (or (contains? managed-labels label)
      (some #(str/starts-with? label %) managed-label-prefixes)))

(defn desired-labels [topology issue]
  (cond-> #{(str "program:" (name (:topology/id topology)))
            (str "layer:" (name (or (:issue/layer issue) :unspecified)))
            (str "priority:" (name (or (:issue/priority issue) :p2)))}
    (seq (:issue/blocked-by issue)) (conj "blocked")))

(defn projectable-issue?
  "Private control-plane observations must never cross a forge boundary.
  Explicit denial wins over every projection ID or topology setting."
  [issue]
  (and (not= false (:issue/projectable? issue))
       (not= :local-private (:issue/visibility issue))
       (not= :communication (:issue/type issue))))

(defn radicle-plan
  "Only Tamaki-managed labels are removed; human-added labels are preserved."
  [topology observed]
  (let [by-id (into {} (map (juxt :forge/id identity)) observed)
        rid (:topology/radicle-repo topology)]
    (->> (:topology/issues topology)
         (filter projectable-issue?)
         (mapcat
          (fn [issue]
            (if-let [id (or (projection-id issue :radicle)
                            (when (re-matches #"[0-9a-f]{40}"
                                              (str (:issue/id issue)))
                              (:issue/id issue)))]
              (let [actual (get by-id id)
                    wanted-labels (desired-labels topology issue)
                    actual-labels (:labels actual)
                    add (sort (set/difference wanted-labels actual-labels))
                    delete (sort
                            (filter managed-label?
                                    (set/difference actual-labels
                                                    wanted-labels)))
                    wanted-status (desired-radicle-status
                                   (:issue/status issue))]
                (cond-> []
                  (nil? actual)
                  (conj {:action :missing :issue/id id})
                  (and actual
                       (or (not= (:issue/title issue) (:title actual))
                           (not= (or (:issue/description issue) "")
                                 (:description actual))))
                  (conj {:action :edit :issue/id id
                         :command
                         ["rad" "issue" "edit" "--no-announce"
                          "--repo" rid id
                          "--title" (:issue/title issue)
                          "--description" (or (:issue/description issue) "")]})
                  (and actual (seq add))
                  (conj {:action :label-add :issue/id id :labels add
                         :command
                         (into ["rad" "issue" "label" "--no-announce"
                                "--repo" rid id]
                               (mapcat #(vector "--add" %) add))})
                  (and actual (seq delete))
                  (conj {:action :label-delete :issue/id id :labels delete
                         :command
                         (into ["rad" "issue" "label" "--no-announce"
                                "--repo" rid id]
                               (mapcat #(vector "--delete" %) delete))})
                  (and actual (not= wanted-status (:status actual)))
                  (conj {:action :state :issue/id id :status wanted-status
                         :command ["rad" "issue" "state" "--no-announce"
                                   "--repo" rid
                                   (str "--" (name wanted-status)) id]})))
              [])))
         vec)))

(defn apply-plan! [plan project]
  (mapv
   (fn [{:keys [command] :as operation}]
     (if command
       (let [result (delivery/execute! command project)]
         (assoc operation
                :result (select-keys result [:exit :out :err])
                :ok? (zero? (:exit result))))
       (assoc operation :ok? false :error :projection-missing)))
   plan))
