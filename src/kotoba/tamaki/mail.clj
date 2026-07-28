(ns kotoba.tamaki.mail
  "Policy boundary for mail capabilities.

  Tamaki never owns provider credentials. A transport adapter receives an
  approved command and must return a durable, redacted receipt."
  (:require [clojure.string :as str]))

(def read-actions #{:mail/sync :mail/search :mail/read})
(def compose-actions #{:mail/draft})
(def send-actions #{:mail/send :mail/reply})
(def actions (into #{} (concat read-actions compose-actions send-actions)))

(defn- nameable?
  "True for values safe to pass to `clojure.core/name`. Keywords, strings,
  and symbols are accepted; other types (numbers, maps, etc.) would throw
  an opaque ClassCastException inside `command` without this guard."
  [value]
  (or (keyword? value) (string? value) (symbol? value)))

(defn- present-ref?
  "True for a non-blank identity reference. Keywords and symbols are present
  when they have a non-empty name; strings must also be non-blank. Clojure's
  truthiness treats \"\" as truthy, so a bare org check alone would let an
  empty reporting org through, and `(name 123)` would ClassCastException."
  [value]
  (and (nameable? value) (not (str/blank? (name value)))))

(defn- sha256 [value]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn draft-digest
  "Bind a human decision to exactly the content that was previewed.
  Any recipient, subject, body, attachment, account, or action edit changes
  the digest and invalidates the previous approval."
  [{:keys [org account action recipients subject body attachments]}]
  (sha256
   (pr-str
    (sorted-map
     :account (str account)
     :action (str action)
     :attachments (mapv #(select-keys % [:name :digest :size :content-type])
                        attachments)
     :body (or body "")
     :org (str org)
     :recipients (vec recipients)
     :subject (or subject "")))))

(defn review
  "Build an ephemeral local preview plus the redacted record that may enter
  Tamaki's event stream. Callers must display :mail.review/preview locally
  but persist only :mail.review/record."
  [request]
  (let [digest (draft-digest request)]
    {:mail.review/preview
     (select-keys request [:org :account :action :recipients :subject
                           :body :attachments])
     :mail.review/record
     {:mail/draft-digest digest
      :mail/account-ref (:account request)
      :mail/action (:action request)
      :mail/recipient-count (count (:recipients request))
      :mail/attachment-count (count (:attachments request))
      :mail/private-content? true}}))

(defn approval-receipt
  "Create a redacted receipt for the draft that the human actually reviewed."
  [request decision reviewer]
  {:mail.approval/status decision
   :mail.approval/draft-digest (draft-digest request)
   :mail.approval/reviewer reviewer
   :mail.approval/private-content? true})

(defn approved?
  "True only when an explicit approval is bound to the unchanged draft."
  [request receipt]
  (and (= :approved (:mail.approval/status receipt))
       (= (draft-digest request)
          (:mail.approval/draft-digest receipt))))

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
  "Build a governed command for an injected mail transport.
  A send/reply command is executable only when its approval receipt matches
  the exact draft digest. Approval-required does not mean executable."
  [{:keys [org account action recipients subject body query attachments
           approval] :as request}]
  (let [verdict (decision request)
        delivery? (contains? send-actions action)
        approved-delivery? (and delivery? (approved? request approval))]
    ;; Fail closed before any `(name …)` call: a non-nameable org used to
    ;; ClassCastException instead of the documented validation error, and a
    ;; blank string org is truthy in Clojure so it must be rejected explicitly.
    (when (or (not (present-ref? org))
              (nil? account)
              (str/blank? (str account)))
      (throw (ex-info "Mail command requires org and account reference"
                      {:org org :account account})))
    {:mail/org org
     :mail/account-ref account
     :mail/action action
     :mail/recipients (vec recipients)
     :mail/subject subject
     :mail/body body
     :mail/attachments (vec attachments)
     :mail/query query
     :mail/decision verdict
     :mail/draft-digest (when delivery? (draft-digest request))
     :mail/approval-bound? approved-delivery?
     :mail/review-required? (and delivery? (not approved-delivery?))
     :mail/executable? (or (= :autonomous verdict) approved-delivery?)
     :mail/credential-material? false}))
