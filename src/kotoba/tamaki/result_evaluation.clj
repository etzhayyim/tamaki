(ns kotoba.tamaki.result-evaluation
  "Evidence-gated evaluation and pairwise tournaments for integrated results.

  Scores are projections, not ground truth. The durable facts remain the
  dimension vector, gates, evidence references, rubric version, and subsequent
  production observations."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def dimensions
  #{:correctness :verification :integration :measured-impact :durability
    :efficiency :novelty :safety :learning-value})

(def weights
  {:correctness 0.15
   :verification 0.15
   :integration 0.15
   :measured-impact 0.20
   :durability 0.10
   :efficiency 0.075
   :novelty 0.05
   :safety 0.10
   :learning-value 0.05})

(def hard-gates
  #{:tests-green? :independent-review? :secret-scan-clean?
    :authority-correct? :human-consent?})

(def validation-windows-ms
  {:seven-day (* 7 24 60 60 1000)
   :thirty-day (* 30 24 60 60 1000)})

(defn- unit-score? [value]
  (and (number? value) (<= 0.0 (double value) 1.0)))

(defn- present? [value]
  (and (some? value) (not (str/blank? (str value)))))

(defn- evidence? [value]
  (and (present? (:evidence/type value))
       (present? (:evidence/ref value))))

(defn validate-evaluation
  [evaluation]
  (let [scores (:evaluation/scores evaluation)
        gates (:evaluation/gates evaluation)
        evidence (:evaluation/evidence evaluation)
        confidence (:evaluation/confidence evaluation)]
    (when-not (present? (:evaluation/id evaluation))
      (throw (ex-info "Evaluation requires :evaluation/id" {})))
    (when-not (present? (:evaluation/result evaluation))
      (throw (ex-info "Evaluation requires :evaluation/result" {})))
    (when-not (pos-int? (:evaluation/rubric-version evaluation))
      (throw (ex-info "Evaluation requires a positive rubric version" {})))
    (when-not (= dimensions (set (keys scores)))
      (throw (ex-info "Evaluation score dimensions are incomplete"
                      {:missing (set/difference dimensions (set (keys scores)))
                       :unknown (set/difference (set (keys scores))
                                                dimensions)})))
    (when-not (every? unit-score? (vals scores))
      (throw (ex-info "Evaluation scores must be in [0,1]" {})))
    (when-not (= hard-gates (set (keys gates)))
      (throw (ex-info "Evaluation hard gates are incomplete"
                      {:missing (set/difference hard-gates
                                                (set (keys gates)))})))
    (when-not (every? boolean? (vals gates))
      (throw (ex-info "Evaluation gates must be boolean" {})))
    (when-not (and (seq evidence) (every? evidence? evidence))
      (throw (ex-info "Evaluation requires typed evidence references" {})))
    (when-not (unit-score? confidence)
      (throw (ex-info "Evaluation confidence must be in [0,1]" {})))
    (when-not (unit-score? (or (:evaluation/risk-penalty evaluation) 0.0))
      (throw (ex-info "Evaluation risk penalty must be in [0,1]" {})))
    evaluation))

(defn score
  "Compute a dashboard score. Failed hard gates produce zero, but callers must
  retain the dimension vector rather than rank unlike domains by this scalar."
  [evaluation]
  (let [evaluation (validate-evaluation evaluation)
        passed? (every? true? (vals (:evaluation/gates evaluation)))
        weighted (reduce-kv
                  (fn [total dimension weight]
                    (+ total (* weight
                                (double
                                 (get-in evaluation
                                         [:evaluation/scores dimension])))))
                  0.0 weights)
        risk (double (or (:evaluation/risk-penalty evaluation) 0.0))]
    (if passed?
      (max 0.0
           (min 1.0
                (- (* (double (:evaluation/confidence evaluation)) weighted)
                   risk)))
      0.0)))

(defn evaluate
  [evaluation evaluated-at]
  (let [evaluation (validate-evaluation evaluation)]
    (assoc evaluation
           :evaluation/evaluated-at evaluated-at
           :evaluation/score (score evaluation)
           :evaluation/status
           (if (every? true? (vals (:evaluation/gates evaluation)))
             :awaiting-production-validation
             :rejected-by-gate)
           :evaluation/validation-due
           (into {}
                 (map (fn [[window duration]]
                        [window (+ evaluated-at duration)]))
                 validation-windows-ms))))

(defn validation
  "Record an ex-post observation. Impact is never inferred from integration."
  [{:keys [evaluation-id result window observed-score evidence regression?]}
   observed-at]
  (when-not (and (present? evaluation-id) (present? result))
    (throw (ex-info "Validation requires evaluation and result IDs" {})))
  (when-not (contains? validation-windows-ms window)
    (throw (ex-info "Unknown validation window" {:window window})))
  (when-not (unit-score? observed-score)
    (throw (ex-info "Observed score must be in [0,1]" {})))
  (when-not (and (seq evidence) (every? evidence? evidence))
    (throw (ex-info "Production validation requires evidence" {})))
  {:validation/id (str (name window) "/" evaluation-id)
   :validation/evaluation evaluation-id
   :validation/result result
   :validation/window window
   :validation/observed-score (double observed-score)
   :validation/evidence evidence
   :validation/regression? (boolean regression?)
   :validation/observed-at observed-at})

(defn- expected [left right]
  (/ 1.0 (+ 1.0 (Math/pow 10.0 (/ (- right left) 400.0)))))

(defn tournament
  "Run deterministic Elo updates over evidence-bearing pairwise matches.
  Elo is meaningful only inside one issue and rubric."
  [{:tournament/keys [id issue rubric-version candidates matches]
    :as tournament}]
  (when (or (not (present? id)) (not (present? issue))
            (not (pos-int? rubric-version))
            (< (count candidates) 2)
            (not= (count candidates) (count (set candidates))))
    (throw (ex-info "Invalid tournament identity or candidates" {})))
  (let [candidate-set (set candidates)
        ratings
        (reduce
         (fn [ratings {:match/keys [left right winner evidence]}]
           (when-not (and (candidate-set left) (candidate-set right)
                          (not= left right)
                          (contains? #{left right :draw} winner)
                          (seq evidence)
                          (every? evidence? evidence))
             (throw (ex-info "Invalid evidence-bearing tournament match"
                             {:left left :right right :winner winner})))
           (let [left-rating (double (get ratings left 1500.0))
                 right-rating (double (get ratings right 1500.0))
                 expected-left (expected left-rating right-rating)
                 actual-left (cond (= winner left) 1.0
                                   (= winner :draw) 0.5
                                   :else 0.0)
                 delta (* 32.0 (- actual-left expected-left))]
             (assoc ratings
                    left (+ left-rating delta)
                    right (- right-rating delta))))
         (zipmap candidates (repeat 1500.0))
         matches)]
    (assoc tournament
           :tournament/elo ratings
           :tournament/ranking
           (->> ratings
                (sort-by (fn [[candidate rating]]
                           [(- rating) (str candidate)]))
                (mapv first)))))
