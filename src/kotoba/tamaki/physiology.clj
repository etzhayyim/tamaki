(ns kotoba.tamaki.physiology
  "Pure resource homeostasis for a finite-lived Tamaki organism.

  This controller never mines, mints, pays, purchases, or extends a life
  lease. It turns observed stocks and flows into a bounded operating mode and
  an evidence-bearing plan. External economic effects remain HIL-gated."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def dimensions
  [:availability :inference-reserve :storage-reserve
   :replication-reserve :treasury-runway])

(def external-actions
  #{:publish-offer :accept-payment :spend-crypto :buy-capacity
    :rotate-wallet :change-price})

(def control-capabilities
  "Capabilities which may use the small, bounded control reserve while the
  organism is conserving resources. These actors observe and evaluate durable
  outputs; they cannot implement, publish, spend, or extend the lease."
  #{:loop-evaluation :event-observation})

(def effect-capabilities
  #{:implementation :git :radicle :github :issue-create :publish
    :support-reply :email-send :payment :useful-work-offer})

(def control-reserve-statuses
  #{:preserve-memory :reclaim-storage :cognitive-rest})

(defn agent-admission
  "Translate the latest physiology projection into a new-inference boundary.

  Existing work is not killed. In earn mode, only an explicitly bound
  useful-work actor may spend inference tokens."
  [projection actor-spec]
  (let [status (:homeostasis/status projection)
        capabilities (set (:actor/capabilities actor-spec))
        control-actor? (and (every? capabilities control-capabilities)
                            (not-any? capabilities effect-capabilities))]
    (cond
      (nil? projection)
      {:admitted? true :reason :homeostasis-unobserved}

      (= :work status)
      {:admitted? true :reason :homeostasis-work}

      (and (= :earn status)
           (contains? capabilities :useful-work-offer))
      {:admitted? true :reason :homeostasis-useful-work}

      (and (contains? control-reserve-statuses status)
           control-actor?)
      {:admitted? true
       :reason :homeostasis-control-reserve
       :homeostasis/status status
       :homeostasis/action (:homeostasis/action projection)}

      :else
      {:admitted? false
       :reason :homeostasis-throttle
       :homeostasis/status status
       :homeostasis/action (:homeostasis/action projection)})))

(defn renew-human-authority
  "Record an explicit local operator authorization in a private observation.
  This does not invent provider quota, treasury, storage, or economic facts."
  [observation now-ms]
  (assoc observation
         :human-authority-valid? true
         :human-authority-observed-at now-ms))

(defn clamp [n]
  (-> (double (or n 0.0)) (max 0.0) (min 1.0)))

(defn read-policy [path]
  (let [file (io/file path)]
    (when-not (.isFile file)
      (throw (ex-info "Homeostasis policy file not found" {:path path})))
    (edn/read-string (slurp file))))

(defn validate-policy [policy]
  (let [required [:organism/id :organism/expires-at
                  :homeostasis/targets :economic/policy]
        missing (filterv #(nil? (get policy %)) required)
        economic (:economic/policy policy)]
    (when (seq missing)
      (throw (ex-info "Homeostasis policy is incomplete" {:missing missing})))
    (when-not (= :earn-useful-work (:economic/mode economic))
      (throw (ex-info
              "Only evidence-backed useful-work earning is supported"
              {:economic/mode (:economic/mode economic)})))
    (when (or (:allow-mining? economic) (:allow-self-mint? economic))
      (throw (ex-info "Mining and self-minting are outside Tamaki authority"
                      {:economic/policy economic})))
    (when-not (false? (:auto-settle-crypto? economic))
      (throw (ex-info "Crypto settlement must be explicitly HIL-gated"
                      {:required {:auto-settle-crypto? false}})))
    policy))

(defn safe-div [n d]
  (if (and (number? d) (pos? d)) (/ (double (or n 0)) (double d)) 0.0))

(defn reserve-score [available target]
  (clamp (safe-div available target)))

(defn stock-scores
  [policy observation]
  (let [targets (:homeostasis/targets policy)
        token-cap (or (:inference-token-reserve targets) 1)
        storage-cap (or (:storage-free-bytes targets) 1)
        replicas-cap (or (:durable-replicas targets) 1)
        runway-cap (or (:treasury-runway-days targets) 1)]
    {:availability
     (clamp (:availability observation))
     :inference-reserve
     (reserve-score (:inference-token-reserve observation) token-cap)
     :storage-reserve
     (reserve-score (:storage-free-bytes observation) storage-cap)
     :replication-reserve
     (reserve-score (:durable-replicas observation) replicas-cap)
     :treasury-runway
     (reserve-score (:treasury-runway-days observation) runway-cap)}))

(defn vitality
  "Geometric mean: one exhausted life-support stock cannot be hidden by a
  surplus elsewhere."
  [scores]
  (let [values (map #(max 1.0e-9 (double (get scores % 0.0))) dimensions)]
    (Math/pow (reduce * values) (/ 1.0 (count values)))))

(defn flow-state [observation]
  {:inference-token-flow
   (- (double (or (:inference-tokens-earned observation) 0))
      (double (or (:inference-tokens-spent observation) 0)))
   :storage-byte-flow
   (- (double (or (:storage-bytes-reclaimed observation) 0))
      (double (or (:storage-bytes-written observation) 0)))
   :credit-flow
   (- (double (or (:murakumo-credits-earned observation) 0))
      (double (or (:murakumo-credits-spent observation) 0)))
   :crypto-flow
   (- (double (or (:crypto-received observation) 0))
      (double (or (:crypto-spent observation) 0)))})

(defn- lowest-stock [scores]
  (first (sort-by (juxt val key) scores)))

(defn decide
  "Return a deterministic physiology projection.

  `murakumo credits` are non-redeemable usage claims. `crypto` is a distinct,
  externally settled asset. Generated inference tokens earn value only when a
  payer accepted a useful-work offer and a verifiable receipt exists."
  [policy observation now-ms]
  (let [policy (validate-policy policy)
        expires-at (:organism/expires-at policy)
        scores (stock-scores policy observation)
        vitality-score (vitality scores)
        [bottleneck score] (lowest-stock scores)
        authority-max-age (:human-authority-max-age-ms policy)
        authority-at (:human-authority-observed-at observation)
        authority-fresh?
        (or (nil? authority-max-age)
            (and (number? authority-at)
                 (<= 0 (- now-ms authority-at) authority-max-age)))
        consent? (and (true? (:human-authority-valid? observation))
                      authority-fresh?)
        receipt? (true? (:useful-work-receipt-valid? observation))
        paid-demand? (true? (:paid-demand? observation))
        mode (cond
               (>= now-ms expires-at) :expired
               (not consent?) :paused
               ;; Imminent local storage exhaustion can corrupt the authority
               ;; stream, so its emergency boundary precedes replication.
               (< (:storage-reserve scores) 0.10) :reclaim-storage
               (< (:replication-reserve scores) 0.5) :preserve-memory
               (< (:storage-reserve scores) 0.35) :reclaim-storage
               (< (:inference-reserve scores) 0.25) :cognitive-rest
               (< (:treasury-runway scores) 0.5) :earn
               :else :work)
        action (case mode
                 :expired :terminate
                 :paused :consult-human
                 :preserve-memory :replicate-sealed-memory
                 :reclaim-storage :reconcile-storage-policy
                 :cognitive-rest :route-local-small-model
                 :earn (if (and paid-demand? receipt?)
                         :request-settlement-approval
                         :publish-useful-work-offer)
                 :work :continue-highest-leverage-work)
        external? (contains? external-actions action)]
    {:homeostasis/status mode
     :homeostasis/vitality vitality-score
     :homeostasis/stocks scores
     :homeostasis/flows (flow-state observation)
     :homeostasis/bottleneck bottleneck
     :homeostasis/bottleneck-score score
     :homeostasis/action action
     :homeostasis/external-effect? external?
     :homeostasis/hil-required?
     (or external? (= action :request-settlement-approval))
     :homeostasis/authority
     {:lease-valid? (< now-ms expires-at)
      :human-authority-valid? consent?
      :human-authority-fresh? authority-fresh?
      :may-extend-own-life? false
      :may-self-mint? false
      :may-mine? false
      :may-auto-settle-crypto? false}
     :homeostasis/economy
     {:unit-of-work :verified-generated-token
      :internal-settlement :murakumo-credit
      :external-settlement :x402
      :crypto-is-not-minted-by-tamaki true
      :receipt-required true}}))

(defn event [projection observation now-ms]
  {:tamaki.event/version 1
   :tamaki.event/id (str (random-uuid))
   :tamaki.event/run (str "homeostasis::" (:organism/id observation "tamaki"))
   :tamaki.event/parent nil
   :tamaki.event/kind :organism/homeostasis-observed
   :tamaki.event/at now-ms
   :tamaki.event/data
   {:homeostasis projection
    ;; Deliberately retain normalized resource quantities, never wallet
    ;; addresses, keys, payer identity, prompts, or generated content.
    :observation
    (select-keys observation
                 [:organism/id :availability :inference-token-reserve
                  :inference-tokens-earned :inference-tokens-spent
                  :storage-free-bytes :storage-bytes-written
                  :storage-bytes-reclaimed :durable-replicas
                  :treasury-runway-days :murakumo-credits-earned
                  :murakumo-credits-spent :crypto-received :crypto-spent
                  :paid-demand? :useful-work-receipt-valid?
                  :human-authority-valid?
                  :human-authority-observed-at])}})
