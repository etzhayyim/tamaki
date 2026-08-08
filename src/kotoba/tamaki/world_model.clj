(ns kotoba.tamaki.world-model
  "Executable, bounded system-dynamics world models.

  Models are data.  An LLM may propose typed mutations, but only this
  deterministic evaluator can select a successor and project it to XMILE."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def operators #{:+ :- :* :/ :min :max :abs})
(def mutation-levels #{:parameter :equation :structure})

(defn- finite-number? [value]
  (and (number? value) (Double/isFinite (double value))))

(defn- fail! [message data]
  (throw (ex-info message (assoc data :world-model/valid? false))))

(defn expression-references [expression]
  (cond
    (keyword? expression) #{expression}
    (number? expression) #{}
    (vector? expression)
    (let [[operator & arguments] expression]
      (when-not (contains? operators operator)
        (fail! "Unsupported world-model operator" {:operator operator}))
      (apply set/union #{} (map expression-references arguments)))
    :else (fail! "World-model expression must be a number, keyword, or vector"
                 {:expression expression})))

(defn- expression-size [expression]
  (if (vector? expression)
    (inc (reduce + (map expression-size (rest expression))))
    1))

(defn- eval-expression [expression resolve-name]
  (cond
    (number? expression) (double expression)
    (keyword? expression) (double (resolve-name expression))
    (vector? expression)
    (let [[operator & arguments] expression
          values (mapv #(eval-expression % resolve-name) arguments)]
      (case operator
        :+ (reduce + 0.0 values)
        :- (if (= 1 (count values)) (- (first values)) (reduce - values))
        :* (reduce * 1.0 values)
        :/ (reduce (fn [left right]
                     (when (zero? right)
                       (fail! "Division by zero in world model"
                              {:expression expression}))
                     (/ left right))
                   values)
        :min (apply min values)
        :max (apply max values)
        :abs (Math/abs (double (first values)))))
    :else (fail! "Invalid world-model expression" {:expression expression})))

(defn names-in [model]
  (set/union (set (keys (:world-model/stocks model)))
             (set (keys (:world-model/variables model)))
             (set (keys (:world-model/parameters model)))))

(defn validate
  "Fail closed on dangling references, algebraic cycles, missing units, or
  stock-flow mismatches. Returns the unchanged model when valid."
  [model]
  (when-not (= 1 (:world-model/version model))
    (fail! "Unsupported world-model version"
           {:version (:world-model/version model)}))
  (when-not (and (finite-number? (:world-model/time-step model))
                 (pos? (double (:world-model/time-step model))))
    (fail! "World-model time step must be positive" {}))
  (let [stocks (:world-model/stocks model)
        variables (:world-model/variables model)
        parameters (:world-model/parameters model)
        all-names (names-in model)
        declared (concat stocks variables parameters)]
    (when (empty? stocks)
      (fail! "World model requires at least one stock" {}))
    (doseq [[name spec] declared]
      (when-not (and (keyword? name) (not (str/blank? (:units spec))))
        (fail! "Every world-model element requires a keyword name and units"
               {:name name :spec spec})))
    (doseq [[name {:keys [value bounds]}] parameters]
      (when-not (finite-number? value)
        (fail! "Parameter value must be numeric" {:parameter name :value value}))
      (when (and bounds
                 (not (<= (double (first bounds))
                          (double value)
                          (double (second bounds)))))
        (fail! "Parameter value is outside declared bounds"
               {:parameter name :value value :bounds bounds})))
    (doseq [[name spec] variables]
      (when-not (contains? #{:flow :aux} (:kind spec))
        (fail! "World-model variable kind must be :flow or :aux"
               {:name name :kind (:kind spec)}))
      (let [missing (set/difference (expression-references (:equation spec))
                                    all-names)]
        (when (seq missing)
          (fail! "World-model equation has dangling references"
                 {:name name :missing missing}))))
    (doseq [[name {:keys [initial inflows outflows units]}] stocks]
      (when-not (finite-number? initial)
        (fail! "Stock initial value must be numeric" {:stock name}))
      (let [missing (set/difference (set (concat inflows outflows))
                                    (set (keys variables)))]
        (when (seq missing)
          (fail! "Stock references unknown flows"
                 {:stock name :missing missing})))
      (doseq [flow-name (concat inflows outflows)]
        (let [flow (get variables flow-name)
              expected-units (str units "/step")]
          (when-not (= :flow (:kind flow))
            (fail! "Stock inflow/outflow must reference a flow"
                   {:stock name :variable flow-name :kind (:kind flow)}))
          (when-not (= expected-units (:units flow))
            (fail! "Flow units must match stock units per step"
                   {:stock name :flow flow-name :expected expected-units
                    :actual (:units flow)})))))
    ;; Resolve every variable once; recursive resolution detects algebraic
    ;; cycles before any candidate can become executable.
    (let [cache (atom {})
          visiting (atom #{})]
      (letfn [(resolve-name [name]
                (cond
                  (contains? @cache name) (get @cache name)
                  (contains? parameters name) (:value (get parameters name))
                  (contains? stocks name) (:initial (get stocks name))
                  (contains? @visiting name)
                  (fail! "Algebraic cycle in world model" {:name name})
                  (contains? variables name)
                  (do (swap! visiting conj name)
                      (let [value (eval-expression
                                   (:equation (get variables name)) resolve-name)]
                        (swap! visiting disj name)
                        (swap! cache assoc name value)
                        value))
                  :else (fail! "Unknown world-model name" {:name name})))]
        (doseq [name (keys variables)] (resolve-name name))))
    model))

(defn forecast
  "Euler one-step forecast. `state` supplies observed stock values and
  `action` may override declared parameters for a bounded intervention."
  [model state action]
  (validate model)
  (let [stocks (:world-model/stocks model)
        variables (:world-model/variables model)
        parameters (:world-model/parameters model)
        dt (double (:world-model/time-step model))
        cache (atom {})
        visiting (atom #{})]
    (doseq [[name value] action]
      (when-not (contains? parameters name)
        (fail! "Action may override declared parameters only" {:name name}))
      (when-not (finite-number? value)
        (fail! "Action value must be finite" {:name name :value value}))
      (when-let [[lower upper] (:bounds (get parameters name))]
        (when-not (<= (double lower) (double value) (double upper))
          (fail! "Action is outside parameter bounds"
                 {:name name :value value :bounds [lower upper]}))))
    (letfn [(resolve-name [name]
              (cond
                (contains? @cache name) (get @cache name)
                (contains? action name) (double (get action name))
                (contains? parameters name) (double (:value (get parameters name)))
                (contains? stocks name) (double (get state name
                                                     (:initial (get stocks name))))
                (contains? @visiting name)
                (fail! "Algebraic cycle during forecast" {:name name})
                (contains? variables name)
                (do (swap! visiting conj name)
                    (let [value (eval-expression
                                 (:equation (get variables name)) resolve-name)]
                      (swap! visiting disj name)
                      (swap! cache assoc name value)
                      value))
                :else (fail! "Unknown name during forecast" {:name name})))]
      (let [environment (into {} (map (fn [name] [name (resolve-name name)]))
                              (keys variables))
            next-state
            (into {}
                  (map (fn [[name {:keys [initial inflows outflows]}]]
                         (let [current (double (get state name initial))
                               incoming (reduce + 0.0 (map resolve-name inflows))
                               outgoing (reduce + 0.0 (map resolve-name outflows))]
                           [name (+ current (* dt (- incoming outgoing)))])))
                  stocks)]
        {:world-model/prediction next-state
         :world-model/environment environment}))))

(defn apply-mutation [model {:candidate/keys [level operations] :as candidate}]
  (when-not (contains? mutation-levels level)
    (fail! "Unknown world-model mutation level" {:candidate candidate}))
  (validate
   (reduce
    (fn [result {:keys [op name value equation spec]}]
      (case op
        :set-parameter
        (do (when-not (= :parameter level)
              (fail! "Parameter mutation requires :parameter level" {:op op}))
            (when-not (contains? (:world-model/parameters result) name)
              (fail! "Cannot mutate unknown parameter" {:name name}))
            (assoc-in result [:world-model/parameters name :value] value))

        :set-equation
        (do (when-not (contains? #{:equation :structure} level)
              (fail! "Equation mutation has the wrong level" {:op op}))
            (when-not (contains? (:world-model/variables result) name)
              (fail! "Cannot mutate unknown equation" {:name name}))
            (assoc-in result [:world-model/variables name :equation] equation))

        :add-variable
        (do (when-not (= :structure level)
              (fail! "Adding a variable requires :structure level" {:op op}))
            (when (contains? (names-in result) name)
              (fail! "World-model name already exists" {:name name}))
            (assoc-in result [:world-model/variables name] spec))

        (fail! "Unsupported world-model mutation" {:operation op})))
    model operations)))

(defn complexity [model]
  (let [elements (+ (count (:world-model/stocks model))
                    (count (:world-model/variables model))
                    (count (:world-model/parameters model)))
        expression-nodes (reduce + 0
                                 (map (comp expression-size :equation val)
                                      (:world-model/variables model)))]
    (+ (double elements) (* 0.05 expression-nodes))))

(defn prediction-loss [model observations]
  (let [errors
        (for [{:observation/keys [state action next-state]} observations
              [name actual] next-state]
          (let [predicted (get-in (forecast model state action)
                                  [:world-model/prediction name])]
            (when (nil? predicted)
              (fail! "Observation names a non-stock output" {:name name}))
            (/ (Math/abs (- (double predicted) (double actual)))
               (max 1.0 (Math/abs (double actual))))))]
    (if (seq errors)
      (/ (reduce + errors) (double (count errors)))
      (fail! "At least one observed next-state value is required" {}))))

(defn score [model observations {:keys [complexity-weight]
                                  :or {complexity-weight 0.01}}]
  (let [loss (prediction-loss model observations)
        model-complexity (complexity model)]
    {:score/prediction-loss loss
     :score/complexity model-complexity
     :score/total (+ loss (* (double complexity-weight) model-complexity))}))

(defn select-successor
  "Evaluate incumbent plus LLM-authored candidates. Invalid candidates remain
  in the receipt as falsified hypotheses. The incumbent wins ties and changes
  require `min-improvement`."
  [model observations candidates {:keys [min-improvement] :as options}]
  (let [minimum (double (or min-improvement 0.0))
        incumbent-score (score model observations options)
        evaluated
        (mapv
         (fn [candidate]
           (try
             (let [candidate-model (apply-mutation model candidate)]
               (assoc candidate
                      :candidate/valid? true
                      :candidate/model candidate-model
                      :candidate/score (score candidate-model observations options)))
             (catch Exception error
               (assoc candidate :candidate/valid? false
                      :candidate/rejection (.getMessage error)
                      :candidate/evidence (ex-data error)))))
         candidates)
        winner (first (sort-by (juxt #(get-in % [:candidate/score :score/total])
                                     #(str (:candidate/id %)))
                               (filter :candidate/valid? evaluated)))
        improvement (when winner
                      (- (:score/total incumbent-score)
                         (get-in winner [:candidate/score :score/total])))
        accepted? (and winner (> improvement minimum))]
    {:world-model.selection/version 1
     :world-model.selection/incumbent-score incumbent-score
     :world-model.selection/candidates
     (mapv #(dissoc % :candidate/model) evaluated)
     :world-model.selection/accepted? (boolean accepted?)
     :world-model.selection/improvement (or improvement 0.0)
     :world-model.selection/candidate (when accepted? (:candidate/id winner))
     :world-model.selection/model (if accepted? (:candidate/model winner) model)}))

(defn- xml-escape [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- xmile-name [name] (-> name clojure.core/name (str/replace "-" "_")))

(defn- expression->xmile [expression]
  (cond
    (number? expression) (str expression)
    (keyword? expression) (xmile-name expression)
    (vector? expression)
    (let [[operator & arguments] expression
          rendered (map expression->xmile arguments)]
      (case operator
        :+ (str "(" (str/join " + " rendered) ")")
        :- (if (= 1 (count rendered))
             (str "(-" (first rendered) ")")
             (str "(" (str/join " - " rendered) ")"))
        :* (str "(" (str/join " * " rendered) ")")
        :/ (str "(" (str/join " / " rendered) ")")
        :min (str "MIN(" (str/join ", " rendered) ")")
        :max (str "MAX(" (str/join ", " rendered) ")")
        :abs (str "ABS(" (first rendered) ")")))))

(defn to-xmile [model]
  (validate model)
  (let [dt (:world-model/time-step model)
        stop (:world-model/horizon model 1)]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         "<xmile xmlns=\"http://docs.oasis-open.org/xmile/ns/XMILE/v1.0\" version=\"1.0\">\n"
         "  <header><name>" (xml-escape (:world-model/id model)) "</name></header>\n"
         "  <sim_specs method=\"Euler\" time_units=\"step\"><start>0</start><stop>"
         stop "</stop><dt>" dt "</dt></sim_specs>\n"
         "  <model><variables>\n"
         (apply str
                (for [[name {:keys [initial inflows outflows units]}]
                      (:world-model/stocks model)]
                  (str "    <stock name=\"" (xmile-name name) "\"><eqn>" initial
                       "</eqn>" (apply str (map #(str "<inflow>" (xmile-name %) "</inflow>") inflows))
                       (apply str (map #(str "<outflow>" (xmile-name %) "</outflow>") outflows))
                       "<units>" (xml-escape units) "</units></stock>\n")))
         (apply str
                (for [[name {:keys [equation units kind]}]
                      (:world-model/variables model)]
                  (str "    <" (if (= :flow kind) "flow" "aux") " name=\""
                       (xmile-name name) "\"><eqn>" (xml-escape (expression->xmile equation))
                       "</eqn><units>" (xml-escape units) "</units></"
                       (if (= :flow kind) "flow" "aux") ">\n")))
         (apply str
                (for [[name {:keys [value units]}] (:world-model/parameters model)]
                  (str "    <aux name=\"" (xmile-name name) "\"><eqn>" value
                       "</eqn><units>" (xml-escape units) "</units></aux>\n")))
         "  </variables></model>\n</xmile>\n")))

(defn write-xmile! [model path]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)] (.mkdirs parent))
    (spit file (to-xmile model))
    (.getCanonicalPath file)))
