# tamaki 環

`tamaki` is the single CLI for Kotoba's durable agent execution stack. It does
not reimplement the existing runtimes; it gives them one `AgentRun` identity,
append-only event history, state machine, and operator surface.

```text
manimani / itonami
        │ submit AgentRun
        ▼
      tamaki ───────▶ kotoba-fleet lease/governor
        │                     │
        │                     ▼
        │                 murakumo node
        │                     │
        └─────────────────────▼
                          kotoba-code
                               │
                               ▼
                    checkpoint/artifact/receipt
```

## Commands

```sh
bin/tamaki submit "make the tests pass" \
  --project /absolute/path/to/repo --execute

bin/tamaki submit "add the requested helper and tests" \
  --mode fleet \
  --repo kotoba-lang/kotoba-delta \
  --pin c8c405abda731e5889aff3ae2a18ac208b375bb5 \
  --node auto \
  --requires git,nbb

bin/tamaki run <run-id>
bin/tamaki status
bin/tamaki status <run-id>
bin/tamaki resume <run-id>
bin/tamaki agents
bin/tamaki nodes --nodes asher,naphtali --requires git,nbb
bin/tamaki tick --work-dir work --max 3 --nodes asher,naphtali
bin/tamaki infer probe
bin/tamaki infer plan qwen-agentworld-35b-a3b
bin/tamaki murakumo status
bin/tamaki doctor
bin/tamaki contract
```

`submit` is safe by default: it only appends a queued run. Add `--execute` or
invoke `run` separately to cross the execution boundary.

`exec` is for **deterministic** work — an ingest tick, a scheduled report — where
there is no model in the loop:

```sh
bin/tamaki exec "innen record tick 2026-07-25" \
  --project /path/to/loop-innen \
  -- nbb --classpath "../innen/src:src:scripts" scripts/tick.cljs --depth 2
```

Everything after `--` is the caller's own argv, run in `--project`, and recorded
with the same lifecycle every other run emits (`submitted -> leased -> started ->
succeeded|failed`) carrying the real `:agent.run/command` and exit code. Mode is
`:external`, which `doctor` gates on the event store alone — a deterministic tick
needs neither `kotoba-code` nor a fleet node. The subprocess's exit code becomes
`exec`'s exit code, so launchd/cron sees the truth without parsing output.

`local` mode always hands the goal to `kotoba-code`, i.e. to a model. Recording a
data tick that way would claim an agent did work it did not do; `exec` exists so
residents can register real runs honestly.

`nodes` and `tick` delegate to `kotoba-fleet`; `infer` and `murakumo` delegate
to the existing Murakumo operators. Their remaining arguments pass through
unchanged, so Tamaki stays one operator entry point without forking those
projects' command contracts.

## Sovereign Radicle delivery

Tamaki can own the complete issue-to-canonical loop without a hosted forge:

```sh
bin/tamaki issue create "Add bounded retries" \
  --project "$PWD" --description "Implement and test bounded retries."

bin/tamaki work issue <issue-id> \
  --project "$PWD" --model codex: --execute

bin/tamaki deliver <run-id> \
  --issue <issue-id> \
  --paths src/kotoba/tamaki/retry.clj,test/kotoba/tamaki/retry_test.clj \
  --message "Add bounded retries"

bin/tamaki review <patch-id> \
  --run <run-id> --tests "bb test; clojure -M:test; clojure -X:test"

# Refuses unless the review receipt exists and approval is explicit.
bin/tamaki integrate <patch-id> \
  --run <run-id> --issue <issue-id> --tests "all suites green" --approve
```

`deliver` refuses non-succeeded runs and requires an explicit path allowlist.
It opens a draft patch via `git push rad HEAD:refs/patches`. `integrate`
records an accepted Radicle review, fast-forwards the canonical branch to the
reviewed patch ref, then pushes that branch to the `rad` remote. Issue, commit,
patch, review, and integration receipts are appended to the same Tamaki event
store as the AgentRun. Successful integration marks the Radicle issue solved.

## Persistent growth loop

An improvement campaign repeats the Radicle delivery cycle while keeping every
cycle bounded and independently auditable:

```sh
bin/tamaki loop start \
  --project "$PWD" \
  --objective "raise maturity, coverage, reliability, and documentation" \
  --model codex: \
  --max-cycles 10 --max-failures 3 --interval-ms 60000

bin/tamaki loop tick <loop-id>   # exactly one resumable cycle
bin/tamaki loop run <loop-id>    # continue until a bound is reached
bin/tamaki loop status <loop-id>
```

Each cycle requires a clean tree, creates a Radicle issue, runs a fresh
AgentRun, discovers its bounded diff, opens and reviews a patch, and records
the result. By default the campaign pauses at a reviewed patch. Add
`--auto-approve` to `loop start` only for a trusted repository to enable
accepted, fast-forward-only integration. `--max-cycles`, `--max-failures`,
and the durable event log are circuit breakers; restarting `loop run` resumes
recorded state rather than resetting its memory.

Before execution, each cycle reads the open Radicle backlog, extracts
`Blocked by: <issue-id>` and `Acceptance: <criterion>` metadata, rejects
dependency cycles, and ranks only unblocked work. The deterministic leverage
score combines impact, urgency, confidence, feedback pressure, risk, effort,
and current WIP pressure. Selection inputs and ranking are durable
`:issue/prioritized` receipts. A separate child AgentRun performs a read-only
review of the resulting patch; integration is attempted only after it passes
the criteria without changing the tree. `:effect/measured` records the
before/after operational signal so later cycles can respond to the feedback.

## Dogfood

Run Tamaki against its own checkout, then inspect the durable lifecycle:

```sh
run_id=$(bin/tamaki submit "add one focused test and run both suites" \
  --project "$PWD" | bb -e '(println (:agent.run/id (read)))')
bin/tamaki run "$run_id"
bin/tamaki status "$run_id"
bin/tamaki agents "$run_id"
```

## Ownership

- `tamaki`: AgentRun contract, run tree, lifecycle and adapters.
- `kotoba-code`: model/tool ReAct loop, test gate, rollback and checkpoint.
- `kotoba-fleet`: distributed lease, capability placement, proposal/governor.
- `murakumo`: Tailscale node transport, reconciliation and inference.
- `scheduler`: portable bounded tick semantics.
- `manimani` / `itonami`: work discovery, policy, approval and business effects.
- Kotobase Datom: shared durable event/checkpoint backend.

The default adapter persists an append-only local `.tamaki/events.edn`.
For one shared run tree across the Mac fleet:

```sh
export TAMAKI_STORE=kotobase
export KOTOBA_URL=https://kotobase.net
export KOTOBA_GRAPH=<shared-graph-cid>
export KOTOBA_TOKEN=<scoped-bearer-token>

bin/tamaki submit "inspect the failing build" --project /path/to/repo
bin/tamaki status
bin/tamaki agents
```

Every machine using the same graph sees the same runs. `TAMAKI_STORE=dual`
makes Kotobase the commit point and appends a local audit/cache copy only after
the shared write succeeds. Remote failure is fail-closed; Tamaki never invents
a local-only event that the rest of the fleet cannot observe.

## Verify

```sh
bb test
clojure -M:test
bin/tamaki doctor
```
