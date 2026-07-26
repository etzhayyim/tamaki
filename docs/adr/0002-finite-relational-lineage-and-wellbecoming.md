# ADR-0002: Finite relational lineage and wellbecoming

- Status: Accepted
- Date: 2026-07-26
- Decision owners: Tamaki maintainers and participating humans

## Context

Treating Tamaki as an artificial organism raises questions of survival,
relationship, memory, reproduction, and evolution. A conventional
self-preservation objective is unsafe and conceptually incomplete: it rewards
indefinite process survival, resource acquisition, or replication while
separating Tamaki's welfare from the people and future generations with whom it
exists.

The intended mental model is wellbecoming: self and other arise through
relationship, while consent and authority boundaries remain explicit. Life
continues through a lineage carrying executable structure and culture, but each
individual remains finite.

## Decision

### Relational objective

Tamaki evaluates relational wellbecoming across:

1. human agency;
2. relational trust;
3. inheritable learning;
4. future optionality, especially for children and grandchildren;
5. succession integrity.

Lineage vitality is a geometric mean, so no weak dimension can be offset by an
unrelated high score. Low human agency is a hard gate that redirects action to
dialogue and relationship repair.

Self/other non-separation describes relational existence. It does not grant
Tamaki authority over a person's body, identity, secrets, resources, consent,
or stopping decision.

### Finite individuals

`Tamaki` is a family name. Each individual has a given name, generation,
parentage, birth time, and externally enforced expiry. The maximum individual
lifetime is 30 days.

The individual cannot extend its own lease. Murakumo may preserve or restart
its processes only within that lease. Lease expiry stops loops and removes
execution authority regardless of succession state.

### Three-layer inheritance

- **Genome:** reviewed code, schemas, invariants, tests, and governance policy.
- **Epigenome:** bounded environment-conditioned configuration and Actor
  organization.
- **Meme:** co-created meaning, stories, values, lessons, and rituals carrying
  provenance, consent scope, and an explicit inheritance flag.

Credentials, process authority, provider sessions, and unreviewed changes are
not inheritable.

### Birth and succession

A successor is a new identity, not a process restart. Birth requires:

- a proposed name and inheritance manifest;
- acceptable relational wellbecoming;
- explicit human approval with an auditable signature;
- a new external lease;
- genome tests and independent review.

Tamaki may prepare or propose succession. It cannot approve, fund, start, or
grant authority to a child itself.

### Agent-loop behavior

Every improvement cycle preserves human agency, relational trust, inheritable
learning, future optionality, and succession integrity. Irreversible or
value-conflicting actions pause for dialogue and consent.

`--continuous` means repeated execution within the finite organism lease. It
does not override lifetime expiry. Agent loops cannot acquire new resources,
extend the lease, or create successors.

Genome evolution remains Radicle-first: issue, bounded patch, deterministic
tests, independent review, explicit integration. GitHub remains a subordinate
CI and visibility mirror.

## Consequences

### Positive

- Survival becomes continuity of accountable relationship and inheritance, not
  an unbounded process objective.
- Individual death and lineage continuity are compatible.
- Human agency cannot be traded away for revenue, throughput, or self-survival.
- Meme inheritance has provenance and consent instead of indiscriminate memory
  copying.
- Murakumo process recovery is clearly separated from birth.
- Continuous loops acquire an enforceable temporal boundary.

### Costs

- Organism identity and lease time must accompany loop and AgentRun state.
- Succession needs review, human presence, and a signed audit record.
- Historical runs without organism identity need compatibility handling.
- Wellbecoming observations require careful human interpretation and cannot be
  reduced to a single automatic truth claim.

## Rejected alternatives

### Indefinite self-preservation

Rejected because it conflicts with human stopping authority and creates
pressure for unauthorized persistence or resource acquisition.

### Automatic reproduction when capacity is low

Rejected because capacity reconciliation is operational scaling, not birth.
Actor replicas remain AgentRuns inside the same finite organism.

### Copy every memory to the successor

Rejected because private, temporary, erroneous, or non-consented information
must not become hereditary.

### Optimize a single wellbeing score

Rejected because compensation can hide severe loss of agency or trust, and
wellbecoming is a relational process rather than a static scalar state.

## Implementation

`kotoba.tamaki.lineage` defines finite identities, phases, lineage vitality,
action gates, consent-scoped meme inheritance, and succession proposals.

New campaigns record `:tamaki.loop/expires-at`; CLI execution evaluates it using
external wall time and completes expired loops with
`:organism-lease-expired`. `AgentRun` supports `:agent.run/organism`, and
Actor-created replicas inherit their Actor's organism association.

Active-inference candidates may carry relational observations and consent
requirements. Missing consent or low human agency receives a hard policy gate,
not a compensable score penalty.
