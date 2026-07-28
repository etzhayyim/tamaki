(ns kotoba.tamaki.business
  "Durable business KPI and stock/flow projection for Tamaki control loops."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def stock-keys
  [:traffic :unique-visitors :qualified-leads :conversations :proposals
   :won-customers :active-customers :mrr-jpy :cash-jpy
   :untriaged-inbox :support-backlog :awaiting-human :open-incidents])

(def flow-keys
  [:new-qualified-leads :new-conversations :new-proposals :new-wins
   :activations :churned-customers :delta-mrr-jpy :revenue-jpy
   :experiments-shipped :accepted-patches :agent-cost-jpy
   :operational-cost-jpy :churn-risk-mrr-jpy
   :inbound-support :triaged-support :drafted-replies :approved-replies
   :resolved-support :incidents-opened :incidents-recovered])

;; Period MRR change is the North Star numerator and can contract. Forcing it
;; non-negative would hide revenue decline from control-signals and issue
;; prioritization; every other flow remains a non-negative stock transfer.
(def signed-flow-keys
  #{:delta-mrr-jpy})

(def rate-keys
  [:activation-rate :paid-conversion-rate :churn-rate :confidence])

(defn non-negative [value]
  (max 0.0 (double (or value 0.0))))

(defn signed-number [value]
  (double (or value 0.0)))

(defn clamp [value]
  (-> (double (or value 0.0)) (max 0.0) (min 1.0)))

(defn ratio [numerator denominator]
  (if (pos? (double (or denominator 0)))
    (/ (double (or numerator 0)) (double denominator))
    0.0))

(defn normalize-flow-value [key value]
  (if (contains? signed-flow-keys key)
    (signed-number value)
    (non-negative value)))

(defn normalize-observation [observation]
  (-> observation
      (update :period-days #(max 1.0 (non-negative (or % 7))))
      (update :stocks
              #(into {} (map (fn [key] [key (non-negative (get % key))])
                             stock-keys)))
      (update :flows
              #(into {} (map (fn [key]
                               [key (normalize-flow-value key (get % key))])
                             flow-keys)))
      (update :rates
              #(into {}
                     (keep (fn [key]
                             (when (contains? % key)
                               [key (clamp (get % key))])))
                     rate-keys))))

(defn derived-rates [{:keys [stocks flows rates]}]
  (merge
   {:activation-rate
    (ratio (:activations flows) (:new-qualified-leads flows))
    :paid-conversion-rate
    (ratio (:new-wins flows) (:new-qualified-leads flows))
    :churn-rate
    (ratio (:churned-customers flows)
           (+ (:active-customers stocks) (:churned-customers flows)))}
   rates))

(defn risk-adjusted-delta-mrr
  "North Star: signed period ΔMRR * confidence, minus churn risk and costs.
  Contraction must remain negative so control-signals raise revenue pressure
  instead of treating decline as a zero-growth plateau."
  [{:keys [flows rates] :as observation}]
  (let [rates (derived-rates observation)
        confidence (clamp (or (:confidence rates) 0.5))
        churn-risk-mrr (non-negative (:churn-risk-mrr-jpy flows))]
    (- (* (signed-number (:delta-mrr-jpy flows)) confidence)
       churn-risk-mrr
       (non-negative (:operational-cost-jpy flows))
       (non-negative (:agent-cost-jpy flows)))))

(defn read-targets [path]
  (let [targets (edn/read-string (slurp (io/file path)))]
    (when-not (map? targets)
      (throw (ex-info "Business targets must be an EDN map" {:path path})))
    targets))

(defn latest-observation
  ([events] (latest-observation events nil))
  ([events domain]
   (some->> events
           (filter #(= :business/observed (:tamaki.event/kind %)))
           (filter #(or (nil? domain)
                        (= domain
                           (get-in % [:tamaki.event/data :observation
                                      :domain]))))
           (sort-by :tamaki.event/at)
           last :tamaki.event/data :observation
           normalize-observation)))

(defn recent-observations [events]
  (->> events
       (filter #(= :business/observed (:tamaki.event/kind %)))
       (sort-by :tamaki.event/at)
       (mapv #(normalize-observation
               (get-in % [:tamaki.event/data :observation])))))

(defn portfolio-observation
  "Aggregate the latest observation per domain without allowing one stale
  provider to erase fresh portfolio evidence. Stocks and flows are additive;
  confidence is the mean of the included observations. The selected and stale
  domain sets remain explicit for audit and UI disclosure."
  [events]
  (let [latest-by-domain
        (->> (recent-observations events)
             (reduce (fn [latest observation]
                       (assoc latest (:domain observation) observation))
                     {}))
        fresh (->> (vals latest-by-domain)
                   (remove #(false? (:fresh? %)))
                   vec)
        stale (->> (vals latest-by-domain)
                   (filter #(false? (:fresh? %)))
                   (keep :domain)
                   (sort-by str)
                   vec)
        add-section
        (fn [section keys]
          (into {}
                (map (fn [key]
                       [key (reduce + 0.0
                                    (map #(double (get-in % [section key] 0.0))
                                         fresh))]))
                keys))]
    (when (seq latest-by-domain)
      (if (seq fresh)
        {:source :metrics/portfolio
         :domain :portfolio
         :domains (->> fresh (keep :domain) (sort-by str) vec)
         :stale-domains stale
         :fresh? true
         :collected-at (apply max 0 (keep :collected-at fresh))
         :source-observed-at
         (apply max 0 (keep :source-observed-at fresh))
         :period-days (apply max 1.0 (map :period-days fresh))
         :stocks (add-section :stocks stock-keys)
         :flows (add-section :flows flow-keys)
         :rates
         {:confidence
          (/ (reduce + (map #(double (get-in % [:rates :confidence] 0.0))
                            fresh))
             (double (count fresh)))}}
        ;; Preserve the newest stale fact so summary continues to fail closed.
        (last (sort-by :collected-at (vals latest-by-domain)))))))

(defn target-progress [actual target]
  (if (pos? (double (or target 0)))
    (clamp (/ (double (or actual 0)) (double target)))
    0.0))

(defn summary
  ([events targets] (summary events targets nil))
  ([events targets domain]
  (if-let [observation (if domain
                         (latest-observation events domain)
                         (portfolio-observation events))]
    (if (false? (:fresh? observation))
      {:business/status :stale
       :business/observation observation
       :business/kpis {}
       :business/progress {}
       :business/control-score 0.0
       :business/targets targets}
    (let [{:keys [stocks flows period-days]} observation
          rates (derived-rates observation)
          weeks (/ period-days 7.0)
          experiments-per-week (ratio (:experiments-shipped flows) weeks)
          agent-cost-per-patch
          (ratio (:agent-cost-jpy flows) (:accepted-patches flows))
          risk-adjusted (risk-adjusted-delta-mrr
                         (assoc observation :rates rates))
          kpis {:traffic (:traffic stocks)
                :unique-visitors (:unique-visitors stocks)
                :active-customers (:active-customers stocks)
                :revenue-jpy (:revenue-jpy flows)
                :mrr-jpy (:mrr-jpy stocks)
                :risk-adjusted-delta-mrr-jpy risk-adjusted
                :qualified-leads (:qualified-leads stocks)
                :activation-rate (:activation-rate rates)
                :paid-conversion-rate (:paid-conversion-rate rates)
                :churn-rate (:churn-rate rates)
                :experiments-per-week experiments-per-week
                :agent-cost-per-accepted-patch-jpy agent-cost-per-patch}
          progress
          {:mrr (target-progress (:mrr-jpy kpis) (:target/mrr-jpy targets))
           :risk-adjusted-growth
           (target-progress risk-adjusted
                            (:target/risk-adjusted-delta-mrr-jpy targets))
           :experiments
           (target-progress experiments-per-week
                            (:target/experiments-per-week targets))
           :activation
           (target-progress (:activation-rate rates)
                            (:target/activation-rate targets))
           :conversion
           (target-progress (:paid-conversion-rate rates)
                            (:target/paid-conversion-rate targets))
           :retention
           (if (pos? (:target/max-churn-rate targets 0))
             (clamp (- 1.0
                       (ratio (:churn-rate rates)
                              (:target/max-churn-rate targets))))
             0.0)}]
      {:business/status :observed
       :business/observation observation
       :business/kpis kpis
       :business/progress progress
       :business/control-score
       (/ (reduce + (vals progress)) (double (count progress)))
       :business/targets targets}))
    {:business/status :unobserved
     :business/kpis {}
     :business/progress {}
     :business/control-score 0.0
     :business/targets targets})))

(defn control-signals [business-summary]
  (if (= :observed (:business/status business-summary))
    (let [progress (:business/progress business-summary)
          kpis (:business/kpis business-summary)
          targets (:business/targets business-summary)
          revenue-gap (- 1.0 (:risk-adjusted-growth progress 0.0))
          experiment-gap (- 1.0 (:experiments progress 0.0))
          churn (double (:churn-rate kpis 0.0))
          max-churn (double (:target/max-churn-rate targets 0.05))
          cost (double (:agent-cost-per-accepted-patch-jpy kpis 0.0))
          max-cost (double (:target/max-agent-cost-per-patch-jpy targets
                            10000.0))]
      {:impact (clamp revenue-gap)
       :urgency (clamp (max revenue-gap experiment-gap))
       :confidence
       (clamp (get-in business-summary
                      [:business/observation :rates :confidence] 0.5))
       :risk (clamp (ratio churn max-churn))
       :effort (clamp (ratio cost max-cost))
       :business-pressure (clamp (- 1.0
                                   (:business/control-score business-summary)))})
    {:impact 0.5 :urgency 0.5 :confidence 0.0 :risk 0.5 :effort 0.5
     :business-pressure 1.0}))

(defn stock-flow [events targets]
  (let [business (summary events targets)
        observation (:business/observation business)
        stocks (:stocks observation)
        flows (:flows observation)]
    {:status (:business/status business)
     :control-score (:business/control-score business)
     :kpis (:business/kpis business)
     :targets targets
     :stocks
     [{:id "traffic" :label "Traffic" :value (or (:traffic stocks) 0)
       :unit "visits" :color "#56b4ff"}
      {:id "qualified-leads" :label "Qualified leads"
       :value (or (:qualified-leads stocks) 0)
       :unit "leads" :color "#62e6b1"}
      {:id "proposals" :label "Proposals" :value (or (:proposals stocks) 0)
       :unit "proposals" :color "#ffb34d"}
      {:id "active-customers" :label "Active customers"
       :value (or (:active-customers stocks) 0)
       :unit "customers" :color "#d06cff"}
      {:id "mrr" :label "MRR" :value (or (:mrr-jpy stocks) 0)
       :unit "JPY" :color "#73f4a1"}]
     :flows
     [{:id "qualify" :label "qualify" :from "traffic" :to "qualified-leads"
       :rate (or (:new-qualified-leads flows) 0)}
      {:id "propose" :label "propose" :from "qualified-leads" :to "proposals"
       :rate (or (:new-proposals flows) 0)}
      {:id "win" :label "win" :from "proposals" :to "active-customers"
       :rate (or (:new-wins flows) 0)}
      {:id "expand" :label "ΔMRR" :from "active-customers" :to "mrr"
       :rate (or (:delta-mrr-jpy flows) 0)}
      {:id "churn" :label "churn" :from "active-customers" :to "environment"
       :rate (or (:churned-customers flows) 0)}]}))

(defn event [observation now-ms]
  {:tamaki.event/version 1
   :tamaki.event/id (str (random-uuid))
   :tamaki.event/run "business::portfolio"
   :tamaki.event/parent nil
   :tamaki.event/kind :business/observed
   :tamaki.event/at now-ms
   :tamaki.event/data {:observation (normalize-observation observation)}})
