# ADR-0005: Evidence-gated result evaluation

Status: Accepted

Date: 2026-07-27

## Context

Agent activity, token use, commits, and even merged patches are not evidence of
useful outcomes. Tamaki needs to improve loops using durable source, test,
review, integration, and production observations without rewarding persuasive
agent prose or optimizing one opaque scalar.

## Decision

1. Result evaluations are EDN facts persisted as typed Tamaki events.
2. The canonical evaluation retains a versioned dimension vector, hard gates,
   typed evidence references, confidence, and risk penalty.
3. The scalar score is a dashboard projection. It is zero when any hard gate
   fails and must not be used to compare unlike domains.
4. Pairwise Elo tournaments operate only within one issue and rubric. Every
   match requires evidence; Elo is an internal preference signal, not ground
   truth.
5. Integration creates `integrated-unvalidated` stock, not validated value.
   Seven-day and thirty-day observations move results toward validated value or
   regression debt.
6. Loop-gardener may propose ActorSpec, LoopSpec, rubric, or resource changes
   from these facts. Existing authority and approval gates still govern those
   changes.

## System dynamics

`backlog -> WIP -> review -> integrated-unvalidated -> validated-value`

Regressions flow from validated value to regression debt. Missing evaluation is
represented explicitly as evaluation debt.

## Consequences

Tamaki can optimize verified outcomes per time, cost, and risk rather than
agent activity. Delayed evaluation is visible and does not masquerade as
success. Evaluation adds operational work and requires domain-specific
observations, but this cost is preferable to self-reinforcing auto-evaluation.

