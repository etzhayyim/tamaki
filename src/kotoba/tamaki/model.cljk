(ns kotoba.tamaki.model
  "Tamaki adapter for the shared AgentRun contract.

  The state machine, budget defaults, transitions and event fold are owned by
  `kotoba-lang/agent`. Tamaki only supplies its own event attribute prefix.

  Same shape as `kotoba.tamaki.capability` over `kotoba-core-contracts`:
  compatibility aliases keep Tamaki callers source-compatible while making
  the shared library the single definition site. 18 namespaces here read
  these vars; rewriting them all at once would be a large change with no
  behavioural payoff and would make the extraction harder to review.

  The `:tamaki.event/*` prefix is preserved deliberately — it is persisted
  data, present 231 times across this repo including `store` and `storage`.
  `agent.run/event-keys` exists so a shared library never demands a database
  migration as the price of adoption."
  (:require [agent.run :as run]))

(def event-ns "tamaki.event")

(def ^:private ks (run/event-keys event-ns))

;; ── contract, statuses, transitions — the library's, verbatim ────────────

(def contract-version run/contract-version)
(def terminal-statuses run/terminal-statuses)
(def transitions run/transitions)

(defn run-id
  ([now-ms] (run/run-id now-ms))
  ([now-ms entropy] (run/run-id now-ms entropy)))

(defn agent-run [spec now-ms] (run/agent-run spec now-ms))

(defn transition [r status now-ms attrs] (run/transition r status now-ms attrs))

;; ── events — the only prefix-dependent surface ───────────────────────────

(defn event [r kind now-ms data] (run/event ks r kind now-ms data))

(defn apply-event [r ev] (run/apply-event ks r ev))

(defn fold-events [events] (run/fold-events ks events))

(defn resumable? [r] (run/resumable? r))
