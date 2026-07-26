(ns kotoba.tamaki.result
  "Project durable execution events into code/patch/PR result graphs.")

(defn node [type id label]
  {:result.node/id (str (name type) "/" id)
   :result.node/type type
   :result.node/value id
   :result.node/label label})

(defn edge [from to type]
  {:result.edge/from (:result.node/id from)
   :result.edge/to (:result.node/id to)
   :result.edge/type type})

(defn- event-for [events run-id kind patch-id]
  (last (filter #(and (= run-id (:tamaki.event/run %))
                      (= kind (:tamaki.event/kind %))
                      (= patch-id
                         (get-in % [:tamaki.event/data :patch/id])))
                events)))

(defn result-graphs
  "One immutable graph per delivered patch. Natural-language activity is
  deliberately ignored; only typed delivery/review/integration evidence enters."
  [events runs candidates]
  (let [run-by-id (into {} (map (juxt :agent.run/id identity)) runs)
        candidate-by-patch
        (into {} (keep (fn [[_ candidate]]
                         (when-let [patch (:evolution/patch-id candidate)]
                           [patch candidate])))
                       candidates)]
    (->> events
         (filter #(= :patch/created (:tamaki.event/kind %)))
         (mapv
          (fn [event]
            (let [data (:tamaki.event/data event)
                  run-id (:tamaki.event/run event)
                  patch-id (:patch/id data)
                  issue (node :issue (:issue/id data) "Issue")
                  commit (node :source (:commit/id data) "Source")
                  patch (node :radicle patch-id "Radicle Patch")
                  candidate (get candidate-by-patch patch-id)
                  pr (when-let [url (:evolution/pr-url candidate)]
                       (node :github url "GitHub PR"))
                  review-event (event-for events run-id
                                          :review/independent patch-id)
                  review (when review-event
                           (node :review patch-id
                                 (name (get-in review-event
                                               [:tamaki.event/data
                                                :review/verdict]))))
                  merge-event (event-for events run-id
                                         :patch/integrated patch-id)
                  merge (when merge-event
                          (node :merge patch-id "Merged"))
                  nodes (vec (remove nil?
                                     [issue commit patch pr review merge]))
                  chain (vec (remove nil? [issue commit patch pr review merge]))]
              {:result/id (str "result/" patch-id)
               :result/run run-id
               :result/project
               (or (:agent.run/source-project (get run-by-id run-id))
                   (:agent.run/project (get run-by-id run-id)))
               :result/issue (:issue/id data)
               :result/patch patch-id
               :result/nodes nodes
               :result/edges
               (mapv (fn [[a b]]
                       (edge a b :result/produced))
                     (partition 2 1 chain))}))))))
