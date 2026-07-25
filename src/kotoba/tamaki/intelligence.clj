(ns kotoba.tamaki.intelligence
  "Deterministic issue graph selection and system-dynamics scoring."
  (:require [clojure.string :as str]))

(def default-signals
  {:impact 0.5 :urgency 0.5 :confidence 0.7 :risk 0.2 :effort 0.5
   :feedback-pressure 0.0 :wip-pressure 0.0})

(defn normalize [value]
  (-> (double (or value 0.0)) (max 0.0) (min 1.0)))

(defn issue-node [{:keys [id title blockers criteria signals status]
                   :or {blockers [] criteria [] signals {} status :open}}]
  {:issue/id id :issue/title title :issue/status status
   :issue/blockers (set blockers) :issue/criteria (vec criteria)
   :issue/signals (merge default-signals signals)})

(defn graph [issues]
  (into {} (map (juxt :issue/id identity) issues)))

(defn cyclic? [issues]
  (let [nodes (graph issues)]
    (letfn [(visit [id path done]
              (cond
                (contains? path id) true
                (contains? done id) false
                :else (some #(visit % (conj path id) (conj done id))
                            (filter nodes (:issue/blockers (nodes id))))))]
      (boolean (some #(visit % #{} #{}) (keys nodes))))))

(defn solved? [node] (contains? #{:solved :closed} (:issue/status node)))

(defn eligible? [nodes node]
  (and (= :open (:issue/status node))
       (every? #(some-> (get nodes %) solved?)
               (:issue/blockers node))))

(defn leverage-score [node]
  (let [{:keys [impact urgency confidence risk effort feedback-pressure
                wip-pressure]} (:issue/signals node)
        benefit (+ (* 0.34 (normalize impact))
                   (* 0.20 (normalize urgency))
                   (* 0.18 (normalize confidence))
                   (* 0.18 (normalize feedback-pressure)))
        drag (+ (* 0.06 (normalize risk))
                (* 0.03 (normalize effort))
                (* 0.01 (normalize wip-pressure)))]
    (- benefit drag)))

(defn rank [issues]
  (when (cyclic? issues)
    (throw (ex-info "Issue blocker graph contains a cycle" {})))
  (let [nodes (graph issues)]
    (->> issues
         (filter #(eligible? nodes %))
         (sort-by (juxt (comp - leverage-score) :issue/id))
         vec)))

(defn selection [issues]
  (when-let [selected (first (rank issues))]
    {:issue selected
     :score (leverage-score selected)
     :blocked-count (count (remove #(eligible? (graph issues) %) issues))
     :candidate-count (count issues)
     :ranking (mapv #(select-keys % [:issue/id :issue/title])
                    (rank issues))}))

(defn acceptance-criteria [objective]
  [(str "A focused change measurably advances: " objective)
   "All documented deterministic test suites pass"
   "No paths outside the selected issue scope are changed"
   "An independent review AgentRun observes the patch without modifying it"
   "The canonical Radicle branch contains the reviewed commit"])

(defn dynamics-signals [{:keys [failures max-failures active-runs open-issues]}]
  {:feedback-pressure (normalize (/ (double failures)
                                    (max 1.0 (double max-failures))))
   :wip-pressure (normalize (/ (double active-runs) 4.0))
   :urgency (normalize (/ (double open-issues) 10.0))})

(defn effect [before after]
  {:effect/tests-delta (- (or (:tests after) 0) (or (:tests before) 0))
   :effect/assertions-delta (- (or (:assertions after) 0)
                               (or (:assertions before) 0))
   :effect/failures-delta (- (or (:failures after) 0)
                             (or (:failures before) 0))
   :effect/improved? (and (<= (or (:failures after) 0)
                            (or (:failures before) 0))
                          (or (> (or (:tests after) 0) (or (:tests before) 0))
                              (> (or (:assertions after) 0)
                                 (or (:assertions before) 0))))})

(defn parse-issue-list [output]
  (->> (str/split-lines (or output ""))
       (keep (fn [line]
               (when-let [[_ id title]
                          (re-find #"│\s*●\s+([0-9a-f]{7,40})\s+(.+?)\s{2,}" line)]
                 (issue-node {:id id :title (str/trim title)}))))
       vec))

(defn parse-issue-metadata [output]
  (let [lines (str/split-lines (or output ""))
        blockers (->> lines
                      (keep #(second (re-find
                                      #"(?i)blocked by:\s*([0-9a-f]{7,40})" %)))
                      set)
        criteria (->> lines
                      (keep #(second (re-find #"(?i)acceptance:\s*(.+)" %)))
                      vec)]
    {:issue/blockers blockers :issue/criteria criteria}))
