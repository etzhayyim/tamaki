(ns kotoba.tamaki.capability
  "Versioned authority contract between Tamaki ActorSpecs and Kototama Wasm.

  Actor capabilities describe business responsibility. They never implicitly
  become host authority. An ActorSpec must separately declare which
  capabilities a guest realizes, the exact actor:host imports it requests,
  the grants an authority may issue, bounded limits, and an effect policy."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def contract-version 1)
(def actor-host-namespace "actor:host")
(def actor-host-version 0)
(def substrates #{:local-agent :kototama-wasm})
(def execution-roles #{:control-guest :worker-guest})
(def decisions #{:autonomous :approval-required :voice-required :blocked})

;; Pinned to kotoba-lang/kototama actor:host v0. A version change is an
;; explicit contract migration; unknown imports are never passed through.
(def import-effects
  {:gen-keypair #{:crypto :secret}
   :sign #{:crypto :secret}
   :verify #{:crypto}
   :sha256-hex #{:crypto}
   :http-post #{:network-write}
   :http-post-headers #{:network-write}
   :http-fetch #{:network-read}
   :log-read #{:storage-read}
   :log-write #{:storage-write}
   :clock-monotonic #{:clock}
   :llm-infer #{:llm-inference}
   :cbor-encode #{:codec}
   :json-encode #{:codec}
   :json-extract-field #{:codec}})

(def known-imports (set (keys import-effects)))

;; This mapping is intentionally small and explicit. Generic abilities such as
;; :implementation or :review cannot be claimed by a Wasm guest until its
;; concrete git/source/test host capabilities exist in actor:host.
(def capability-imports
  {:organism/heartbeat #{:clock-monotonic :sha256-hex :log-write}
   :telemetry/read #{:log-read}
   :telemetry/write #{:log-write}
   :network/fetch #{:http-fetch}
   :network/post #{:http-post}
   :llm/infer #{:llm-infer}
   :identity/generate #{:gen-keypair}
   :identity/sign #{:sign}
   :identity/verify #{:verify}
   :content/digest #{:sha256-hex}
   :codec/cbor #{:cbor-encode}
   :codec/json #{:json-encode :json-extract-field}})

(defn required-imports [capabilities]
  (reduce set/union #{} (map capability-imports capabilities)))

(defn effects-for [imports]
  (reduce set/union #{} (map import-effects imports)))

(defn- error [kind data]
  (assoc data :error kind))

(defn validate-contract
  "Validate a Kototama execution contract as pure data.

  Expected execution shape:
  {:execution/substrate :kototama-wasm
   :execution/role :control-guest
   :execution/realizes #{:organism/heartbeat}
   :execution/capability-contract
   {:contract/version 1
    :abi/namespace \"actor:host\" :abi/version 0
    :imports #{...} :grants #{...}
    :limits {...}
    :effect-policy {:clock :autonomous ...}}}"
  [actor-capabilities execution]
  (let [substrate (:execution/substrate execution)
        role (:execution/role execution)
        realizes (set (:execution/realizes execution))
        contract (:execution/capability-contract execution)
        imports (set (:imports contract))
        grants (set (:grants contract))
        limits (:limits contract)
        policies (:effect-policy contract)
        unknown-imports (set/difference imports known-imports)
        unknown-grants (set/difference grants known-imports)
        unknown-capabilities (set/difference realizes
                                            (set (keys capability-imports)))
        undeclared-capabilities (set/difference realizes
                                                (set actor-capabilities))
        required (required-imports realizes)
        missing-imports (set/difference required imports)
        missing-grants (set/difference imports grants)
        effects (effects-for imports)
        missing-policies (set/difference effects (set (keys policies)))
        invalid-policies (into {}
                               (remove (fn [[_ decision]]
                                         (contains? decisions decision)))
                               policies)
        network? (seq (set/intersection effects
                                        #{:network-read :network-write}))
        prefixes (:allowed-url-prefixes limits)
        secret? (contains? effects :secret)
        write? (contains? effects :storage-write)
        errors
        (cond-> []
          (not= :kototama-wasm substrate)
          (conj (error :execution/substrate
                       {:expected :kototama-wasm :actual substrate}))

          (not (contains? execution-roles role))
          (conj (error :execution/role {:actual role}))

          (not= contract-version (:contract/version contract))
          (conj (error :contract/version
                       {:expected contract-version
                        :actual (:contract/version contract)}))

          (not= actor-host-namespace (:abi/namespace contract))
          (conj (error :abi/namespace
                       {:expected actor-host-namespace
                        :actual (:abi/namespace contract)}))

          (not= actor-host-version (:abi/version contract))
          (conj (error :abi/version
                       {:expected actor-host-version
                        :actual (:abi/version contract)}))

          (seq unknown-capabilities)
          (conj (error :capabilities/unrealizable
                       {:capabilities unknown-capabilities}))

          (seq undeclared-capabilities)
          (conj (error :capabilities/not-owned
                       {:capabilities undeclared-capabilities}))

          (seq unknown-imports)
          (conj (error :imports/unknown {:imports unknown-imports}))

          (seq unknown-grants)
          (conj (error :grants/unknown {:grants unknown-grants}))

          (seq missing-imports)
          (conj (error :imports/missing-for-capability
                       {:imports missing-imports}))

          (seq missing-grants)
          (conj (error :grants/missing {:imports missing-grants}))

          (seq missing-policies)
          (conj (error :effect-policy/missing
                       {:effects missing-policies}))

          (seq invalid-policies)
          (conj (error :effect-policy/invalid
                       {:policies invalid-policies}))

          (and network? (or (nil? prefixes) (empty? prefixes)
                            (not-every? #(and (string? %)
                                              (not (str/blank? %)))
                                        prefixes)))
          (conj (error :network/allowlist-required {}))

          (and secret? (not (true? (:allow-secret-imports? limits))))
          (conj (error :limits/secret-imports {}))

          (and write? (not (true? (:allow-write-imports? limits))))
          (conj (error :limits/write-imports {}))

          (and (contains? effects :network-write)
               (= :autonomous (:network-write policies)))
          (conj (error :effect-policy/network-write-needs-human {}))

          (and secret? (= :autonomous (:secret policies)))
          (conj (error :effect-policy/secret-needs-human {}))

          (and (contains? imports :http-fetch)
               (not (pos-int? (:max-http-fetches limits))))
          (conj (error :limits/http-fetches {}))

          (and (seq (set/intersection imports
                                      #{:http-post :http-post-headers}))
               (not (pos-int? (:max-http-posts limits))))
          (conj (error :limits/http-posts {}))

          (and (contains? imports :llm-infer)
               (not (pos-int? (:max-llm-infers limits))))
          (conj (error :limits/llm-infers {})))]
    {:ok? (empty? errors)
     :contract/version contract-version
     :execution/substrate substrate
     :execution/role role
     :realizes realizes
     :required-imports required
     :requested-imports imports
     :grants grants
     :effects effects
     :limits limits
     :effect-policy policies
     :errors errors}))

(defn validate!
  [actor-capabilities execution]
  (let [report (validate-contract actor-capabilities execution)]
    (when-not (:ok? report)
      (throw (ex-info "Kototama capability contract rejected" report)))
    report))

(defn execution-envelope
  "Create the only authority payload Tamaki may hand to Kototama.
  Business capabilities and private ActorSpec data are deliberately absent."
  [actor-id actor-capabilities execution]
  (let [report (validate! actor-capabilities execution)]
    {:tamaki.capability/version contract-version
     :tamaki.capability/actor (str actor-id)
     :tamaki.capability/substrate (:execution/substrate report)
     :tamaki.capability/role (:execution/role report)
     :tamaki.capability/abi
     {:namespace actor-host-namespace :version actor-host-version}
     :tamaki.capability/imports (:requested-imports report)
     :tamaki.capability/grants (:grants report)
     :tamaki.capability/limits (:limits report)
     :tamaki.capability/effect-policy (:effect-policy report)}))
