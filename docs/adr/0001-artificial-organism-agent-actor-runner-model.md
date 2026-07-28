# ADR-0001: Separate organism, Actor, AgentRun, Runner, and Model identity

- Status: Accepted
- Date: 2026-07-26
- Decision owners: Tamaki maintainers

## Context

Tamaki operates multiple model CLIs, accounts, persistent loops, scalable
Actors, and concurrent AgentRuns. Provider labels such as `codex` and `claude`
have sometimes appeared as agent or Actor names in operator surfaces.

That naming collapses three different concerns:

1. the durable responsibility that should continue to exist;
2. the bounded execution performing work now;
3. the replaceable provider and model used for that execution.

It prevents reliable identity across model failover, makes multiple accounts or
concurrent runs ambiguous, and gives an inaccurate picture of Tamaki as an
adaptive artificial organism.

## Decision

Etzhayyim uses the following identity and ownership model:

- **Tamaki family** is the set of all repository-bound AOs owned by the
  `etzhayyim` organization.
- **Repository AO** is one artificial organism and governance boundary. The
  relationship is bijective: one repository equals one AO.
- **`tamaki` repository AO** is the family representative, without implicit
  mutation authority over another AO.
- **Actor** is a durable role, objective, policy, and desired capacity.
- **AgentRun** is a unique bounded execution individual.
- **Runner** is an interchangeable CLI/account execution profile.
- **Model** is a selected inference resource owned by a Runner configuration.
- **Activity stream** is telemetry attributed to an AgentRun or to `system`.

`codex`, `claude`, `claude-zai`, and `grok` are Runner identifiers. They must
not be used as Actor identity. Concurrent executions have different AgentRun
IDs even when they use the same Runner, Model, account, worker, or repository.

Actor names describe responsibility, for example `issue-scout` or
`independent-reviewer`. UI surfaces show Actor first, AgentRun second, and
Runner/Model as execution attributes. Agent activity is filterable by AgentRun;
organism and supervisor activity is grouped under `system`.

The durable name of an AO derives from its repository slug
(`ao:github:etzhayyim/<repository>`). A maximum-30-day named life is an
incarnation that stewards an AO; it is not the repository-bound AO itself.
Repository archival makes an AO dormant rather than deleting it.

Actor association is optional for operator-created and legacy AgentRuns until
the contract migration is complete. Its absence must not be inferred from the
Runner name.

## Consequences

### Positive

- Actor identity survives Runner and Model failover.
- Multiple accounts and concurrent executions remain distinguishable.
- Capacity reconciliation can count AgentRuns per Actor without conflating
  providers.
- Usage, limits, activity, and failures can be attributed both to an execution
  and its execution substrate.
- Observatory can present the organism's organizational structure accurately.
- Family membership can be reconciled from the complete Etzhayyim repository
  inventory without committing a private runtime inventory.
- Active inference and self-evolution can modify roles independently from model
  routing.

### Costs

- AgentRun creation and historical projections need an explicit or optional
  Actor association.
- Existing events require fallback attribution.
- Operator surfaces must display more than one label.
- Metrics should specify their grouping dimension: Actor, AgentRun, Runner,
  Model, or system.

## Rejected alternatives

### Treat each provider as an Actor

Rejected because responsibility would change whenever routing changes and
multiple accounts would have unclear identity.

### Treat Actor and AgentRun as synonyms

Rejected because a durable role may scale to multiple concurrent executions and
must retain memory and policy after those executions terminate.

### Give a model process durable identity

Rejected because processes and model sessions are ephemeral and may be retried,
resumed, or replaced without changing the assigned responsibility.

## Implementation guidance

The canonical relationship is:

```edn
{:actor/id :issue-scout}

{:agent.run/id "run-a82f1"
 :agent.run/actor :issue-scout
 :agent.run/runner :codex
 :agent.run/model "gpt-example"}

{:activity/agent "run-a82f1"
 :activity/stream :tool}
```

Runner profile configuration may refer to local account directories, but
credentials and machine-specific paths remain private and outside committed
ActorSpecs and durable public receipts.

See [Tamaki artificial organism model](../artificial-organism.md) for the
operational vocabulary and UI rules.
