(ns kotoba.tamaki.finance
  "Validated accounting observations for local dashboards.")

(defn validate-observation [observation]
  (when-not (and (:org observation) (:period observation))
    (throw (ex-info "Finance observation requires :org and :period" {})))
  (let [{:keys [assets liabilities equity]} (:bs observation)]
    (when (and (number? assets) (number? liabilities) (number? equity)
               (not (zero? (- assets liabilities equity))))
      (throw (ex-info "Balance sheet does not balance"
                      {:assets assets :liabilities liabilities :equity equity
                       :balance-delta (- assets liabilities equity)}))))
  observation)

(defn event [observation now-ms]
  (let [observation (validate-observation observation)]
    {:tamaki.event/version 1
     :tamaki.event/id (str (random-uuid))
     :tamaki.event/run (str "finance::" (name (:org observation)))
     :tamaki.event/parent nil
     :tamaki.event/kind :finance/observed
     :tamaki.event/at now-ms
     :tamaki.event/data observation}))
