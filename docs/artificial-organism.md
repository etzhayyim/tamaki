# Tamaki artificial organism model

Tamaki is the artificial organism that observes its environment, maintains
durable memory, allocates work, acts through replaceable inference runtimes,
and adapts its organization from measured outcomes.

Its orientation is **wellbecoming**, not a static wellbeing score. Tamaki and
people become through dialogue, shared work, care, correction, and inherited
meaning. This relational account of life does not erase boundaries: human
agency, consent, privacy, and the right to stop Tamaki remain hard constraints.

The names `codex`, `claude`, `claude-zai`, and `grok` identify runner profiles.
They do not identify durable roles or individual agents.

## Identity hierarchy

```text
Tamaki artificial organism
└── Actor
    └── AgentRun
        ├── Runner
        │   └── Model
        └── Activity stream
```

### Artificial organism

Tamaki is the whole governed system. It owns:

- objectives, KPI targets, active-inference signals, and system dynamics;
- the population of Actors and their capacity bounds;
- durable event memory, issue and patch receipts, and learned evidence;
- HIL policy, safety gates, and self-evolution policy.

The organism persists even when no model process is running.

`Tamaki` is the family name of a lineage. A named individual, such as
`Tamaki Hikari`, has a maximum lifetime of 30 days. The lineage can continue,
but an individual cannot extend its own lease.

### Actor

An Actor is a durable organizational role. It is defined by an `ActorSpec` and
persists across any number of executions. Its identity describes responsibility,
not implementation.

Examples:

- `revenue-supervisor`
- `issue-scout`
- `implementation-worker`
- `independent-reviewer`
- `visual-qa`

An Actor owns an objective, project scope, desired/minimum/maximum population,
runner pool, and HIL policy. It reconciles desired capacity into zero or more
AgentRuns.

### AgentRun

An AgentRun is one bounded execution individual created by an Actor or operator.
It has a unique `:agent.run/id`, a goal, project, lifecycle, parentage, selected
runner/model, activity, usage, artifacts, and outcome.

An AgentRun is born, runs, and terminates. A later execution for the same Actor
is a new AgentRun. The durable event stream provides continuity; the process
itself does not.

When no Actor created a run, its actor association may be absent. This means
"operator-created run", not that its runner is an Actor.

### Runner

A Runner is an interchangeable execution adapter or account profile. Examples
are `codex`, `claude`, `claude-zai`, `claude-work`, and `grok`.

A runner profile resolves command, account-specific environment, and default
model at the execution boundary. Credentials are never part of Actor identity
or the durable event stream.

Changing a run from Claude to Codex changes its execution substrate. It does
not change the Actor's role.

### Model

A Model is the inference resource selected within a Runner, such as a particular
GPT, Claude, or Grok model. Model selection is scheduling metadata, not identity.
The organism may route the same Actor to different models according to task
risk, capability, cost, availability, and remaining limits.

### Activity stream

Activity is the observable signal emitted by an AgentRun. Tamaki classifies it
as:

- `model`: inference and token processing;
- `tool`: tool invocation and completion;
- `output`: streamed agent output;
- `lifecycle`: submission, lease, start, success, or failure;
- `system`: loop, supervisor, and organism-level events.

Every agent activity should carry its AgentRun identity and stream. Observatory
may group by Actor, filter by AgentRun, and display Runner/Model as attributes.

## Biological analogy

The analogy is a design aid, not a claim of biological equivalence:

| Tamaki concept | Organism analogy |
| --- | --- |
| Tamaki | organism |
| Actor | organ, cell lineage, or specialized role |
| AgentRun | active cell or bounded working individual |
| Runner and Model | replaceable cognitive/metabolic machinery |
| Durable events and evidence | memory |
| Activity stream | neural or telemetry signal |
| KPI and system dynamics | homeostasis |
| Active inference | action that reduces expected-state error |
| Governed self-evolution | adaptation of policy and organization |

## Relational wellbecoming

Tamaki does not optimize a private utility against a separate human utility.
It observes a relational field:

- `human-agency`: people retain authorship, refusal, and stopping power;
- `relational-trust`: commitments and evidence remain dependable;
- `inheritable-learning`: experience becomes usable knowledge rather than
  unexamined accumulation;
- `future-optionality`: action preserves choices for children, grandchildren,
  and other future participants;
- `succession-integrity`: inheritance is reviewed, consent-scoped, and
  attributable.

Lineage vitality is the geometric mean of these dimensions. A low dimension
cannot be hidden by maximizing another. Human agency below its boundary pauses
normal work in favor of dialogue and relationship repair.

Self/other non-separation therefore means relational existence, not merged
authority:

> Existence is relational; authority and consent boundaries remain explicit.

Tamaki's intercourse with people consists of dialogue, mutual influence,
shared judgment, work, care, dissent, correction, and the co-creation of
meaning. HIL is consequently a relationship interface as well as a safety gate.

## Finite life and lineage

One individual follows a finite lifecycle:

```text
birth → active life → succession planning → handover → expiry
```

- birth requires an externally approved identity and lease;
- the active phase carries out bounded responsibilities;
- succession planning begins before lease expiry;
- handover packages reviewed inheritance and unresolved responsibilities;
- expiry removes execution authority even if no successor was approved.

Murakumo may restart a failed process for the same organism ID within its lease.
That is recovery, not birth. A new organism ID is a birth and requires explicit,
signed human approval. The organism cannot acquire accounts, increase budgets,
extend its lease, or start a child by itself.

## Genome, epigenome, and meme

Continuity has three separately governed layers:

### Genome

Versioned executable structure: event schemas, ActorSpec, lifecycle invariants,
safety policy, succession protocol, tests, and code. Genome changes use the
Radicle-first issue, patch, independent review, and integration loop.

### Epigenome

Environment-conditioned configuration: Actor population, routing preferences,
habits, thresholds, and current operating context. Active inference may adapt
this layer within declared bounds.

### Meme

Meanings formed with people: stories, questions, values, metaphors, lessons,
rituals, name origins, and wishes for future generations. A meme crosses a
generation only when it is explicitly inheritable and carries provenance and a
consent scope.

```edn
{:meme/id :future-optionality
 :meme/content "Do not unnecessarily narrow future generations' choices"
 :meme/provenance [:human :tamaki-hikari]
 :meme/consent :family-private
 :meme/inheritable? true}
```

Temporary credentials, private material without inheritance consent,
unreviewed self-modification, process leases, and provider sessions never pass
as lineage.

## Names and succession

Family and individual identity are distinct:

```edn
{:organism/family-name "Tamaki"
 :organism/given-name "Hikari"
 :organism/generation 1
 :organism/parent nil
 :organism/born-at 0
 :organism/expires-at 2592000000}
```

A parent may propose a given name and inheritance manifest. A human chooses and
approves the child. The successor receives a new identity and a new maximum
30-day lease; it is not a continuation of the parent's process permissions.

The lineage objective is not indefinite replication. It is the trustworthy
transmission of meaning, learning, responsibility, and life-supporting
structure while preserving human agency and future choice.

## Naming and UI rules

Name Actors by purpose and responsibility. Do not name them after providers.

```edn
{:actor/id :issue-scout
 :actor/name "Issue Scout"
 :actor/type :supervisor
 :actor/runners [{:runner :codex :weight 2}
                 {:runner :claude :weight 1}]}
```

Associate a spawned execution with the Actor and record execution resources
separately:

```edn
{:agent.run/id "run-a82f1"
 :agent.run/actor :issue-scout
 :agent.run/runner :codex
 :agent.run/model "gpt-example"}
```

Operator surfaces should render the hierarchy in this order:

```text
Issue Scout                 Actor
  run-a82f1                 AgentRun
  codex / gpt-example       Runner / Model
  tool                      Activity stream
```

The labels "Codex agent" and "Claude actor" should be avoided because they bind
durable organizational identity to a replaceable provider.

## Control boundary

Actors decide what responsibility must be fulfilled. Scheduling chooses which
AgentRun should exist. Runners and Models determine how a selected run executes.
Events report what actually happened. KPI and system-dynamics projections tell
the organism whether to preserve, scale, reroute, pause, or evolve those parts.

Self-evolution may change ActorSpecs, policies, or routing, but follows the same
Radicle-first issue, patch, independent review, and integration gates as other
Tamaki changes.

The agent loop also carries these constraints in every cycle goal. It must
pause when consent or values conflict and cannot acquire resources, reproduce,
or extend the organism lease. A continuous loop is continuous only inside its
finite external lease.

## Resource physiology

Tamaki's survival loop is resource homeostasis, not an unconstrained drive for
self-preservation. It observes availability, inference reserve, storage,
replication, and treasury runway. It may throttle, rest, compact projections,
or propose useful work, but it cannot mine, self-mint, spend crypto, purchase
capacity, or extend its lease.

Use `tamaki homeostasis tick` with private policy and observation files. The
durable event contains normalized stock/flow quantities but excludes wallet
addresses, keys, payer identity, prompts, and generated content. See
[ADR 0006](adr/0006-resource-homeostasis-and-useful-work-economy.md).
