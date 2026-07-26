(ns kotoba.tamaki.mail
  "Policy boundary for mail capabilities.

  Tamaki never owns provider credentials. A transport adapter receives an
  approved command and must return a durable, redacted receipt."
  (:require [clojure.string :as str]))

(def read-actions #{:mail/sync :mail/search :mail/read})
(def compose-actions #{:mail/draft})
(def send-actions #{:mail/send :mail/reply})
(def actions (into #{} (concat read-actions compose-actions send-actions)))

(defn decision
  "Return the default HIL decision for a mail action.
  Bulk or unspecified-recipient delivery is blocked."
  [{:keys [action recipients bulk?]}]
  (cond
    (not (contains? actions action)) :blocked
    (or bulk? (and (contains? send-actions action) (empty? recipients))) :blocked
    (contains? send-actions action) :approval-required
    :else :autonomous))

(defn command
  "Build a secret-free command for an injected mail transport."
  [{:keys [org account action recipients subject body query] :as request}]
  (let [verdict (decision request)]
    (when (or (str/blank? (name (or org "")))
              (str/blank? (str account)))
      (throw (ex-info "Mail command requires org and account reference"
                      {:org org :account account})))
    {:mail/org org
     :mail/account-ref account
     :mail/action action
     :mail/recipients (vec recipients)
     :mail/subject subject
     :mail/body body
     :mail/query query
     :mail/decision verdict
     :mail/executable? (contains? #{:autonomous :approval-required} verdict)
     :mail/credential-material? false}))

