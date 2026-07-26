(ns kotoba.tamaki.finance
  "Validated accounting observations for local dashboards.")

(defn validate-observation [observation]
  (when-not (and (:period observation)
                 (or (:org observation)
                     (get-in observation [:owner :ref])))
    (throw (ex-info
            "Finance observation requires :period and :org or :owner/:ref" {})))
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
     :tamaki.event/run
     (str "finance::"
          (name (or (:org observation)
                    (get-in observation [:owner :ref]))))
     :tamaki.event/parent nil
     :tamaki.event/kind :finance/observed
     :tamaki.event/at now-ms
     :tamaki.event/data observation}))
