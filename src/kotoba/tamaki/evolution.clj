(ns kotoba.tamaki.evolution
  "Fail-closed lifecycle for GitHub-backed self-evolution candidates."
  (:require [clojure.string :as str]))

(def statuses
  [:proposed :implemented :tested :reviewed :canary
   :awaiting-human :promoted :rejected])

(def transitions
  {:proposed #{:implemented :rejected}
   :implemented #{:tested :rejected}
   :tested #{:reviewed :rejected}
   :reviewed #{:canary :rejected}
   :canary #{:awaiting-human :rejected}
   :awaiting-human #{:promoted :rejected}
   :promoted #{}
   :rejected #{}})

(defn candidate-id [issue now-ms]
  (str "evolution-gh-" issue "-" now-ms))

(defn candidate
  [{:keys [issue objective project base-commit branch worktree]} now-ms]
  (when-not (and (pos-int? issue)
                 (not (str/blank? objective))
                 (not (str/blank? project))
                 (not (str/blank? base-commit))
                 (not (str/blank? branch))
                 (not (str/blank? worktree)))
    (throw (ex-info "Evolution candidate requires GitHub issue, objective, base, branch and worktree"
                    {:issue issue :project project})))
  {:evolution/version 1
   :evolution/id (candidate-id issue now-ms)
   :evolution/issue issue
   :evolution/objective objective
   :evolution/project project
   :evolution/base-commit base-commit
   :evolution/branch branch
   :evolution/worktree worktree
   :evolution/status :proposed
   :evolution/created-at now-ms
   :evolution/updated-at now-ms})

(defn transition [candidate status now-ms evidence]
  (let [from (:evolution/status candidate)]
    (when-not (contains? (get transitions from #{}) status)
      (throw (ex-info "Invalid evolution transition"
                      {:candidate (:evolution/id candidate)
                       :from from :to status})))
    (merge candidate evidence
           {:evolution/status status :evolution/updated-at now-ms})))

(defn fitness
  [{:keys [tests assertions failures coverage maturity revenue-risk]}]
  (+ (* 0.18 (double (or tests 0)))
     (* 0.02 (double (or assertions 0)))
     (* -1.5 (double (or failures 0)))
     (* 1.0 (double (or coverage 0)))
     (* 1.0 (double (or maturity 0)))
     (* -1.0 (double (or revenue-risk 0)))))

(defn improved? [before after]
  (and (zero? (or (:failures after) 0))
       (> (fitness after) (fitness before))))

(defn promotion-ready? [candidate]
  (and (= :awaiting-human (:evolution/status candidate))
       (pos-int? (:evolution/issue candidate))
       (not (str/blank? (:evolution/pr-url candidate)))
       (:evolution/tests-passed? candidate)
       (:evolution/review-accepted? candidate)
       (:evolution/replay-passed? candidate)
       (:evolution/canary-passed? candidate)
       (improved? (:evolution/fitness-before candidate)
                  (:evolution/fitness-after candidate))))

(defn event [candidate kind now-ms data]
  {:tamaki.event/version 1
   :tamaki.event/id (str (random-uuid))
   :tamaki.event/run (:evolution/id candidate)
   :tamaki.event/parent nil
   :tamaki.event/kind kind
   :tamaki.event/at now-ms
   :tamaki.event/data data})

(defn apply-event [state event]
  (case (:tamaki.event/kind event)
    :evolution/proposed (get-in event [:tamaki.event/data :candidate])
    :evolution/transition
    (transition state
                (get-in event [:tamaki.event/data :status])
                (:tamaki.event/at event)
                (get-in event [:tamaki.event/data :evidence]))
    :evolution/evidence
    (merge state
           (get-in event [:tamaki.event/data :evidence])
           {:evolution/updated-at (:tamaki.event/at event)})
    state))

(defn candidates [events]
  (reduce
   (fn [result event]
     (let [id (:tamaki.event/run event)]
       (if (or (= :evolution/proposed (:tamaki.event/kind event))
               (contains? result id))
         (assoc result id (apply-event (get result id) event))
         result)))
   {} events))
