(ns kotoba.tamaki.active-inference
  "Deterministic active-inference state and expected-free-energy policy choice."
  (:require [kotoba.tamaki.intelligence :as intelligence]
            [kotoba.tamaki.lineage :as lineage]))

(def dimensions [:impact :urgency :confidence :risk :effort
                 :feedback-pressure :wip-pressure])

(defn clamp [value]
  (-> (double (or value 0.0)) (max 0.0) (min 1.0)))

(defn belief-state
  ([observations] (belief-state nil observations))
  ([prior observations]
   (let [prior-means (or (:belief/means prior) {})
         means (into {}
                     (map (fn [dimension]
                            (let [old (get prior-means dimension 0.5)
                                  observed (get observations dimension old)]
                              [dimension (clamp (+ (* 0.65 old)
                                                   (* 0.35 observed)))])))
                     dimensions)
         uncertainty (into {}
                           (map (fn [dimension]
                                  [dimension
                                   (clamp
                                    (Math/abs
                                     (- (get means dimension)
                                        (get observations dimension
                                             (get means dimension)))))]))
                           dimensions)]
     {:belief/version 1
      :belief/means means
      :belief/uncertainty uncertainty
      :belief/observations (select-keys observations dimensions)})))

(defn prediction-error [belief observations]
  (/ (reduce +
             (map (fn [dimension]
                    (Math/abs
                     (- (get-in belief [:belief/means dimension] 0.5)
                        (get observations dimension 0.5))))
                  dimensions))
     (double (count dimensions))))

(defn policy
  [{:keys [id observations wellbecoming requires-consent? consent?]} belief]
  (let [signals (merge intelligence/default-signals observations)
        pragmatic (clamp (intelligence/leverage-score
                          (intelligence/issue-node
                           {:id id :title (str id) :signals signals})))
        epistemic (/ (reduce + (vals (:belief/uncertainty belief)))
                     (double (max 1 (count (:belief/uncertainty belief)))))
        risk (clamp (:risk signals))
        ambiguity (prediction-error belief signals)
        vitality (if wellbecoming
                   (lineage/lineage-vitality wellbecoming)
                   0.5)
        consent-blocked? (and requires-consent? (not consent?))
        agency-blocked? (and wellbecoming
                             (< (lineage/clamp (:human-agency wellbecoming)) 0.5))
        expected-free-energy (+ (if (or consent-blocked? agency-blocked?)
                                  1000000.0 0.0)
                                (- (+ (* 0.40 risk)
                                      (* 0.25 ambiguity))
                                (+ (* 0.25 pragmatic)
                                   (* 0.10 epistemic)
                                   (* 0.15 vitality))))]
    {:policy/id id
     :policy/pragmatic-value pragmatic
     :policy/epistemic-value epistemic
     :policy/risk risk
     :policy/lineage-vitality vitality
     :policy/gate (cond consent-blocked? :approval-required
                        agency-blocked? :repair-relationship
                        :else :allowed)
     :policy/ambiguity ambiguity
     :policy/expected-free-energy expected-free-energy}))

(defn select-policy [belief candidates]
  (first
   (sort-by (juxt :policy/expected-free-energy :policy/id)
            (map #(policy % belief) candidates))))
