(ns kotoba.tamaki.communication
  "Secret-free projection of human communications into Tamaki's issue graph.

  Provider bodies, phone numbers, addresses and credentials stay in the local
  transport. The durable topology receives only stable digests, relationships,
  consent state and outcome metadata."
  (:require [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def channels #{:email :message :phone})
(def directions #{:inbound :outbound})
(def consent-states #{:not-required :required :approved :declined})

(defn digest [value]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (str value) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn communication-id [{:keys [org channel external-id]}]
  (str "communication/" (subs (digest (str org "|" channel "|" external-id))
                              0 24)))

(defn issue
  "Create one canonical issue from one mail, message or call.

  `summary` must already be redacted and is deliberately optional. `blockers`
  contains canonical issue IDs that must resolve before this communication can
  be acted on."
  [{:keys [org channel external-id direction occurred-at participant-refs
           summary blockers status consent outcome]
    :or {participant-refs [] blockers [] status :open
         consent :not-required}}]
  (when-not (contains? channels channel)
    (throw (ex-info "Unsupported communication channel" {:channel channel})))
  (when-not (contains? directions direction)
    (throw (ex-info "Communication direction is required"
                    {:direction direction})))
  (when (or (nil? org) (str/blank? (str external-id)) (nil? occurred-at))
    (throw (ex-info "Communication requires org, external-id and occurred-at"
                    {})))
  (when-not (contains? consent-states consent)
    (throw (ex-info "Unsupported communication consent state"
                    {:consent consent})))
  (let [id (communication-id {:org org :channel channel
                              :external-id external-id})]
    {:issue/id id
     :issue/title (str (str/capitalize (name channel)) " "
                       (name direction) " follow-up")
     :issue/type :communication
     :issue/visibility :local-private
     :issue/projectable? false
     :issue/status status
     ;; Topology and intelligence currently expose the same dependency through
     ;; two query vocabularies. Keep them identical and fail closed.
     :issue/blocked-by (set blockers)
     :issue/blockers (set blockers)
     :issue/criteria ["A redacted outcome receipt is attached"
                      "Every declared blocker is closed or integrated"]
     :communication/channel channel
     :communication/direction direction
     :communication/occurred-at occurred-at
     :communication/participant-digests
     (mapv #(subs (digest %) 0 20) participant-refs)
     :communication/content-digest
     (subs (digest (str external-id "|" occurred-at)) 0 24)
     :communication/summary summary
     :communication/consent consent
     :communication/outcome outcome
     :communication/private-content? true}))

(defn pr-receipt
  "Link communication issues to a source/patch/review result without copying
  their private content into Git or forge metadata."
  [{:keys [communication-issues project issue-id commit-id patch-id
           review-verdict]}]
  (when (empty? communication-issues)
    (throw (ex-info "PR communication receipt requires communication issues"
                    {})))
  {:receipt/version 1
   :receipt/type :communication/pr-history
   :project project
   :issue/id issue-id
   :commit/id commit-id
   :patch/id patch-id
   :review/verdict review-verdict
   :communication/issues
   (mapv (fn [node]
           {:issue/id (:issue/id node)
            :channel (:communication/channel node)
            :direction (:communication/direction node)
            :occurred-at (:communication/occurred-at node)
            :content-digest (:communication/content-digest node)
            :consent (:communication/consent node)})
         communication-issues)
   :privacy/redacted true})
