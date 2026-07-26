# ADR 0004: Federated Tamaki organisms by organization

- Status: accepted
- Date: 2026-07-26

## Context

Etzhayyim, cloud-itonami, jk-luxury, and gftdcojp do not share one objective,
one responsibility boundary, or one acceptable authority model. A single
supervisor state would allow evidence, credentials, budgets, and decisions
from one organization to affect another without an explicit relationship.

## Decision

Tamaki is a shared kernel and a federation of organization-scoped artificial
organisms. Each `OrganismSpec` owns its objective, repository scope, durable
state, actors, loops, budget, governor, identity, and HIL policy.

The federation observatory is read-only. It may compare signed outputs and
system dynamics, but it cannot promote a patch, move money, contact a person,
or transfer credentials across organizations.

```text
Tamaki kernel
├── tamaki/etzhayyim
├── tamaki/cloud-itonami
├── tamaki/jk-luxury
└── tamaki/gftdcojp
```

`cloud-itonami` and `jk-luxury` share `network-awai` as a protocol and
dependency, not as shared state or authority. Cross-organism work is exchanged
as an addressable, signed artifact with provenance, review evidence, intended
recipient, and an explicit acceptance decision.

## Isolation boundaries

The following are never implicitly shared:

- event log and query database;
- Radicle identity and patch namespace;
- GitHub organization permissions;
- credentials and secret broker;
- token, cost, and concurrency budgets;
- actors, loops, KPI definitions, and pruning policy;
- voice/HITL decisions;
- finite lifetime, succession, and lineage.

Repository patterns grant observation scope, not write authority. Effective
authority is the intersection of the organism, governor, actor, repository,
credential, and current human decision.

## Organization responsibilities

### Etzhayyim

Religious activity and relational wellbecoming. It preserves meme and lineage
without treating people as optimization targets. Doctrine, publication,
personal communication, and succession require human participation.

### cloud-itonami

Sustainable business activity around network-awai. It may autonomously observe,
triage, experiment in bounded sandboxes, and prepare patches. Contracts,
payments, customer contact, publication, and integration require approval.

### jk-luxury

Luxury business activity using network-awai while retaining independent
customer, brand, price, and reputation responsibility. Brand expression,
customer contact, pricing, and publication require approval.

### gftdcojp

Defensive cybersecurity and sustainable business value. Observation and
bounded defensive analysis may be autonomous. Credential access, external
network action, disclosure, exploitation, and customer-impacting changes are
blocked or approval-gated.

## Consequences

An organism can fail, expire, or be pruned without taking authority over its
siblings. Shared learning remains possible through explicit artifacts and
meme inheritance. The observatory presents one garden with separate groves,
and every activity retains an organism identity.

