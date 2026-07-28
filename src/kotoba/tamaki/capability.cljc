(ns kotoba.tamaki.capability
  "Tamaki adapter for the shared Kotoba actor capability contract.

  Vocabulary, ABI, effect classes, validation, and envelope shape are owned
  by kotoba-lang/kotoba-core-contracts. Tamaki only supplies actor ownership
  and emits the minimal execution envelope."
  (:require [kotoba.core.actor-capability :as shared]
            [kotoba.core.capability-repository :as repository]))

;; Compatibility aliases keep Tamaki callers source-compatible while making
;; the shared library the single definition site.
(def contract-version shared/envelope-version)
(def actor-host-namespace shared/actor-host-namespace)
(def actor-host-version shared/actor-host-version)
(def substrates shared/substrates)
(def execution-roles shared/execution-roles)
(def decisions shared/decisions)
(def import-effects shared/import-effects)
(def known-imports shared/known-imports)
(def capability-imports shared/capability-imports)
(def required-imports shared/required-imports)
(def effects-for shared/effects-for)

(defn validate-contract [actor-capabilities execution]
  (shared/validate-execution actor-capabilities execution))

(defn validate! [actor-capabilities execution]
  (let [report (validate-contract actor-capabilities execution)]
    (when-not (:ok? report)
      (throw (ex-info "Kototama capability contract rejected" report)))
    report))

(defn execution-envelope [actor-id actor-capabilities execution]
  ;; Validate through the Tamaki compatibility entry point first so existing
  ;; error semantics remain stable, then let the shared library emit.
  (validate! actor-capabilities execution)
  (let [envelope (shared/execution-envelope
                  actor-id actor-capabilities execution)]
    (assoc envelope
           :tamaki.capability/repositories
           (repository/repository-refs-for-imports
            (:tamaki.capability/imports envelope)))))
