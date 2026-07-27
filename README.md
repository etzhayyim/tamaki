# tamaki 環

`tamaki` is the single CLI for Kotoba's durable agent execution stack. It does
not reimplement the existing runtimes; it gives them one `AgentRun` identity,
append-only event history, state machine, and operator surface.

Tamaki models the complete system as an artificial organism. In this model an
Actor is a durable role with an objective and policy, an AgentRun is one
bounded execution created to perform that role, and `codex`, `claude`, or
`grok` are replaceable runner profiles rather than Actor names. See
[Artificial organism model](docs/artificial-organism.md) and
[ADR-0001](docs/adr/0001-artificial-organism-agent-actor-runner-model.md).
Each Tamaki individual has a maximum 30-day external lease. Its continuity is
the governed transmission of genome, epigenome, and consent-scoped memes to a
human-approved successor—not indefinite process survival. See
[ADR-0002](docs/adr/0002-finite-relational-lineage-and-wellbecoming.md).

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

## Concurrent runner pool

Tamaki treats each subscription CLI/account as a distinct, durable worker.
Built-ins are `codex`, `claude` (`claude -p`), and `claude-zai`. Register
additional Claude accounts without putting credentials in the event log:

```sh
export TAMAKI_CLAUDE_ACCOUNTS='claude-work=$HOME/.claude-work,claude-lab=$HOME/.claude-lab'
bin/tamaki runners

bin/tamaki swarm "inspect and improve one bounded issue" \
  --project "$PWD" \
  --runners codex,claude,claude-zai,claude-work,claude-lab \
  --execute
```

`swarm --execute` creates one detached git worktree per runner and starts all
workers concurrently. The AgentRun stores only the runner ID and model; account
configuration is resolved at execution time through `CLAUDE_CONFIG_DIR`.
Worktrees are intentionally retained for review/integration.

For a persistent pool, use `~/.config/tamaki/runners.edn` (or
`TAMAKI_RUNNERS_FILE`):

```clojure
[{:id "claude-work" :model "claude:sonnet" :kind :claude-account
  :env {"CLAUDE_CONFIG_DIR" "/Users/me/.claude-work"}}
 {:id "claude-lab" :model "claude:opus" :kind :claude-account
  :env {"CLAUDE_CONFIG_DIR" "/Users/me/.claude-lab"}}]
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

## Content reaction loop

Tamaki connects private creation repositories to public channels without
putting organization details or credentials in this public repository. The
local control plane declares the content organism, channels, credential
references, and issue authority.

```sh
# Planning is safe and secret-free. It stops at the approval boundary.
bin/tamaki content plan \
  --spec /path/to/private-control/content-loops/example.edn \
  --artifact /path/to/runtime/artifact-manifest.edn

# Repeat with --approve only after a human has inspected the artifact.
# This authorizes a configured provider adapter; the pure Tamaki contract
# itself does not upload or retain provider credentials.

# Provider collectors normalize real post-publication facts.
bin/tamaki content observe --file /path/to/runtime/reaction.edn
bin/tamaki content status --id example
```

Service operations use the same measured event stream to maintain a private
issue topology:

```bash
tamaki service status --spec projects/.tamaki/tamaki-control/services/example.edn
tamaki service reconcile --spec projects/.tamaki/tamaki-control/services/example.edn --execute
```

The deterministic service governor covers observation, incident response,
support triage, response drafting, human decisions, acquisition, activation,
conversion, retention, and 7/30-day outcome validation. Generated nodes are
`:local-private` and non-projectable by default. Raw messages, provider IDs,
addresses, credentials, and bodies remain in the injected mail/data adapter;
only aggregate stocks and redacted result receipts enter Tamaki.

Mail delivery is fail-closed. Before every send or reply, the local review
surface shows the account, recipients, subject, complete body, attachments,
related issue, and reply context. Approval is bound to a SHA-256 digest of
that exact draft. Editing any reviewed field invalidates the receipt and
requires another human review. Tamaki persists only the digest and decision;
the preview content stays in the local-private transport.

```sh
tamaki mail review --file /path/to/private-runtime/draft.edn
```

This opens the native local review dialog and prints only a redacted approval
receipt. Passing that receipt with an edited draft still fails closed.

The feedback decision prioritizes discovery, completion/retention, engagement,
conversion, then a follow-up. Missing observations remain
`:await-observation`; Tamaki never fabricates audience response. Drafting,
rendering, tests, and publication manifests may run autonomously, while
publishing remains `:approval-required` and deletion remains blocked.

Verified accounting exports enter the same local event store:

```sh
bin/tamaki finance observe --file /path/to/private-runtime/accounting.edn
```

The EDN contains `:org`, `:period`, `:currency`, and optional `:pl`, `:bs`,
and `:cf` maps. When all balance-sheet totals are supplied, Tamaki rejects an
observation unless assets equal liabilities plus equity. Individual books and
their values stay in the local runtime store; the public repository contains
only this generic contract.

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
cycle bounded and independently auditable.

### EDN LoopSpec registry (preferred)

Loops are registered as EDN, not hardcoded in the supervisor or ad-hoc shell.
Drop a file under `loops/` (or any directory on `TAMAKI_LOOPS_DIR` /
`TAMAKI_LOOPS_PATH`, or `<cwd>/.tamaki/loops`, `~/.config/tamaki/loops`):

```clojure
;; loops/toshokan-maturity.edn
{:loop/id :toshokan/maturity
 :loop/enabled true
 :loop/objective "さらに成熟度を向上: expand public-domain coverage..."
 :loop/project "orgs/kotoba-lang/toshokan"
 :loop/workspace-env "COM_JUNKAWASAKI_ROOT"  ; resolve project relative to this
 :loop/continuous true
 :loop/interval-ms 900000
 :loop/max-failures 4
 :loop/auto-approve true
 :loop/runners ["codex" "claude" "claude-zai" "grok"]}
```

```sh
# Discover registered loops and any matching active campaign
bin/tamaki loop list

# Validate / ensure / run from the EDN registration
bin/tamaki loop validate loops/toshokan-maturity.edn
bin/tamaki loop ensure --spec loops/toshokan-maturity.edn
bin/tamaki loop ensure-all              # every enabled LoopSpec
bin/tamaki loop ensure-all --dry-run    # plan only
bin/tamaki loop tick <loop-id>
bin/tamaki loop run <loop-id>
bin/tamaki loop status
```

Shipped registrations:

| File | Id | Role |
|------|----|------|
| `loops/revenue-growth.edn` | `:tamaki/revenue-growth` | default resident supervisor loop |
| `loops/toshokan-maturity.edn` | `:toshokan/maturity` | 15m continuous toshokan maturity / coverage |

`bin/tamaki-supervisor` loads `TAMAKI_LOOP_SPEC` (default
`loops/revenue-growth.edn`) instead of baking the objective string into the
shell script. Set `TAMAKI_EXTRA_LOOP_SPEC` explicitly to ensure an additional
registration; it is empty by default because an ensured campaign still needs
its own resident `loop run` process. CLI flags and env vars remain overrides on
top of the EDN base. Campaigns store `:tamaki.loop/spec-id` so objective prose
can change without forking a second campaign.

### Ad-hoc CLI (still supported)

```sh
bin/tamaki loop start \
  --project "$PWD" \
  --objective "raise maturity, coverage, reliability, and documentation" \
  --runner claude-work \
  --max-cycles 10 --max-failures 3 --interval-ms 60000

# Resident supervisor: discovers work forever and rotates managed providers.
bin/tamaki loop start \
  --project "$PWD" \
  --objective "discover and resolve the highest-leverage maturity issue" \
  --organism-name Hikari \
  --runners codex,claude,claude-zai,grok \
  --continuous --auto-approve --interval-ms 900000

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
With `--continuous`, the cycle limit is disabled while the failure circuit
breaker remains active. `--runners` rotates cycles deterministically through
the named Tamaki profiles, so every provider receives the same durable
run/lease/activity/usage and review lifecycle.

`--organism-name` records a named finite individual such as `Tamaki Hikari`.
Its loop lease expires after at most 30 days even with `--continuous`.
`--organism-generation` and `--organism-parent` record lineage. Supplying these
options creates identity metadata only; it does not bypass the separately
reviewed, human-approved succession protocol.

Before execution, each cycle reads the open Radicle backlog, extracts
`Blocked by: <issue-id>` and `Acceptance: <criterion>` metadata, rejects
dependency cycles, and ranks only unblocked work. The deterministic leverage
score combines impact, urgency, confidence, feedback pressure, risk, effort,
and current WIP pressure. Selection inputs and ranking are durable
`:issue/prioritized` receipts. A separate child AgentRun performs a read-only
review of the resulting patch; integration is attempted only after it passes
the criteria without changing the tree. `:effect/measured` records the
before/after operational signal so later cycles can respond to the feedback.

## Revenue control plane

Tamaki treats commercial outcomes as durable facts rather than agent prose.
Private targets live in the gitignored `actors/revenue-targets.edn`; initialize
it from the illustrative public template, then record a periodic observation
from an approved analytics or accounting export:

```sh
cp examples/revenue-targets.example.edn actors/revenue-targets.edn
cp examples/revenue-observation.edn /tmp/revenue-week.edn
# Fill the stocks, period flows, costs, and confidence with observed values.
bin/tamaki kpi observe --file /tmp/revenue-week.edn
bin/tamaki kpi status
```

The control plane projects traffic, qualified leads, conversations, proposals,
won and active customers, MRR, and cash as stocks. Lead creation, activation,
wins, churn, experiments, accepted patches, model/agent cost, operating cost,
and MRR change are period flows. Its North Star is:

```text
risk-adjusted incremental MRR
= delta MRR * confidence
 - churn-risk MRR - operational cost - agent cost
```

Target attainment produces a bounded `0..1` control score. Revenue gap,
experiment cadence, churn, confidence, and cost become active-inference
signals for Radicle Issue selection. Observed business pressure may increase
actor capacity within its declared min/max bounds. Missing KPI data stays
`:unobserved`: it creates prioritization pressure to instrument the system but
does not by itself scale the actor pool.

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
export KOTOBA_URL=https://graph.example.test
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

## Governed self-evolution

Radicle is the source of truth for both normal delivery and changes to
Tamaki's own future behaviour. GitHub is a subordinate mirror used for CI,
visibility, and optional secondary review:

```text
Radicle Issue
  -> isolated evolution/* worktree
  -> implementation
  -> deterministic tests + durable-event replay
  -> Radicle Patch
  -> independent Radicle review
  -> optional draft GitHub mirror PR + CI
  -> canary
  -> fitness comparison
  -> voice approval
  -> Radicle canonical promotion or rejection
```

The canonical tree is never the mutation workspace. Start a candidate only
from a clean canonical checkout:

```sh
bin/tamaki evolve propose 93971f39ceb295136d4769bd4ce3a7a94ddeb030 \
  --project "$PWD" \
  --objective "Implement explicit active inference and safe self-evolution"
```

After committing inside the returned worktree, advance the durable lifecycle:

```sh
bin/tamaki evolve transition CANDIDATE :implemented --commit SHA
bin/tamaki evolve verify CANDIDATE -- clojure -M:test
bin/tamaki evolve open-patch CANDIDATE --title "evolve: active inference"
# Optional GitHub mirror:
bin/tamaki evolve open-pr CANDIDATE --title "evolve: active inference"
bin/tamaki evolve transition CANDIDATE :reviewed --review-accepted true
bin/tamaki evolve canary CANDIDATE -- clojure -M:test
bin/tamaki evolve transition CANDIDATE :awaiting-human \
  --fitness-before '{:tests 68 :assertions 202 :failures 1}' \
  --fitness-after '{:tests 75 :assertions 226 :failures 0}'
bin/tamaki evolve promote CANDIDATE
```

Promotion fails closed unless the candidate has a Radicle Issue and Patch,
green tests, independent review, historical replay, a green canary, improved
fitness, and explicit voice approval. A GitHub PR is deliberately not a
promotion requirement.

The resident supervisor defaults to `TAMAKI_SELF_EVOLUTION_MODE=radicle`.
Setting it to `github` is an explicit fallback that retires active Radicle
campaigns; it is not the normal operating mode.

### Canonical issue topology

Issue dependencies live in a repository-owned EDN roadmap, not in forge issue
prose. Existing Radicle and GitHub issues can be imported once (or explicitly
reconciled), after which EDN is authoritative:

```sh
tamaki topology import \
  --file roadmaps/itonami-kaikei.edn \
  --project . --execute

tamaki topology project \
  --file roadmaps/itonami-kaikei.edn \
  --project .            # preview

tamaki topology project \
  --file roadmaps/itonami-kaikei.edn \
  --project . --execute  # EDN -> Radicle
```

Import matches by forge ID, then exact title, preserves canonical blocker
edges, and records forge provenance under `:issue/projections`. Projection
reconciles title, description, state, and Tamaki-managed labels. Human labels
are preserved. Every executed import/project writes a receipt to Tamaki's
private event store. Actors with `:topology-sync` run the projector at their
reconciliation boundary.

Email, message, and telephone follow-up can participate in the same topology.
One provider interaction becomes one `:issue/type :communication` node; its
`:issue/blocked-by` edges can gate code or human work. Raw bodies, addresses,
numbers, recordings, and credentials remain local. Only redacted outcome
metadata and stable digests may be attached to Issue → Source → Patch → Review
receipts. See [ADR 0004](docs/adr/0004-communication-as-issue-and-redacted-pr-history.md).

### Lifecycle maintenance

Generated actor/swarm worktrees are leases, not permanent checkouts:

```sh
tamaki maintenance status
tamaki maintenance cleanup          # dry-run summary
tamaki maintenance cleanup --execute
```

Cleanup removes only terminal, clean, conflict-free worktrees whose HEAD is
already reachable from the canonical repository. Dirty changes, unique commits,
merge conflicts, and index locks are preserved and reported to the
loop-gardener. The resident supervisor executes this deterministic collector
after every actor reconciliation round; it never uses force removal.

## Evidence-gated result evaluation

Merged code enters an `integrated-unvalidated` stock; integration alone is not
counted as value. Record a versioned score vector and its evidence, compare
alternative results within one issue, then attach seven-day and thirty-day
production observations:

```sh
bin/tamaki result evaluate --file examples/result-evaluation.example.edn
bin/tamaki result tournament --file examples/result-tournament.example.edn
bin/tamaki result validate --file examples/result-validation.example.edn
bin/tamaki result status
```

The Observatory projects evaluation debt, validated value, and regression debt
from the same durable event stream. See
[ADR-0005](docs/adr/0005-evidence-gated-result-evaluation.md).
