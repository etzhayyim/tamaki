(ns kotoba.tamaki.bridge
  "Deterministic Radicle-primary / GitHub-mirror gap reconciliation."
  (:require [clojure.string :as str]))

(def mirrorable-statuses #{:tested :reviewed :canary :awaiting-human})

(defn candidate-gap [candidate]
  (cond
    (and (contains? mirrorable-statuses (:evolution/status candidate))
         (:evolution/patch-id candidate)
         (str/blank? (:evolution/pr-url candidate)))
    {:bridge/action :open-draft-pr
     :bridge/candidate (:evolution/id candidate)
     :bridge/authority :radicle
     :bridge/issue (:evolution/issue candidate)
     :bridge/patch (:evolution/patch-id candidate)}

    (and (:evolution/pr-url candidate)
         (not (:evolution/github-observed-at candidate)))
    {:bridge/action :observe-github
     :bridge/candidate (:evolution/id candidate)
     :bridge/authority :radicle
     :bridge/pr-url (:evolution/pr-url candidate)}

    :else nil))

(defn plan [candidates]
  (->> candidates vals (keep candidate-gap)
       (sort-by (juxt :bridge/action :bridge/candidate)) vec))

(defn github-observation [result now-ms]
  {:evolution/github-observed-at now-ms
   :evolution/github-observation
   {:exit (:exit result)
    :summary (-> (or (:out result) "")
                 str/trim
                 (subs 0 (min 2000 (count (str/trim (or (:out result) ""))))))}})
