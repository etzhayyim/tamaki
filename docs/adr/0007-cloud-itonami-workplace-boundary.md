# ADR-0007: Join Cloud Itonami as an external artificial-organism worker

## Status

Accepted.

## Context

The Tamaki repository is the representative Etzhayyim repository AO. Cloud
Itonami can represent Etzhayyim as an organization and provide a coherent
human workplace for active work, topology, activity, resources, approval, and
stop controls.

If Tamaki were embedded as an application background job, its lifecycle would
become dependent on a UI process, login session, network connection, and
restart-ephemeral model queue. That conflicts with local-first memory,
repository identity, governed incarnation, and independently supervised
homeostasis.

## Decision

Tamaki joins the Etzhayyim organization as an external `OrganismWorker`.

```text
Cloud Itonami / Etzhayyim workplace
    │ assignment, redacted projection, typed intent
    ▼
Tamaki repository AO
    ├── external supervisor
    ├── append-only event authority
    ├── Actors → AgentRuns → Runner / Model
    ├── Radicle-first issue and patch loop
    └── lifecycle, capability, homeostasis and HIL gates
```

`organisms/cloud-itonami-worker.edn` is the public, secret-free assignment
profile. `kotoba.tamaki.workplace` constructs the assignment and admits typed
intents.

The resident supervisor runs `tamaki workplace reconcile --execute` every two
seconds by default. The consumer:

1. reads the full envelope only from private `.tamaki/workplace/inbox`;
2. holds an OS file lock for one intent;
3. correlates a later `:approval/submit` envelope with its parent;
4. evaluates the current incarnation, capability, repository authority,
   homeostasis, and HIL state;
5. commits `:executing` before crossing the effect boundary;
6. dispatches an objective as a bounded AgentRun, or a governed stop as
   durable `:loop/completed` events;
7. atomically records the effect receipt observed by Cloud Itonami.

The execution boundary is at-most-once. If the supervisor crashes after the
`:executing` receipt but before the terminal receipt, automatic reconciliation
does not replay the effect. The receipt remains an explicit recovery item for
the operator. This prefers a visible incomplete effect over accidentally
duplicating source, mail, payment, or other future capabilities.

The workplace may observe safe projections and submit a bounded intent. Intent
admission means only “placed in Tamaki's inbox.” Execution still requires:

1. a live externally granted incarnation lease;
2. an Actor capability and repository-local authority;
3. sufficient homeostatic resource reserve;
4. HIL approval when the effect class requires it;
5. an evidence-bearing result event.

Tamaki retains memory, lifecycle, and source authority. Radicle remains the
primary issue authority. Organization membership never grants cross-AO
mutation authority.

Cloud Itonami outage, logout, or disconnection may make observation stale but
does not stop the supervisor. Reconnection resumes a redacted cursor-based
projection from the last durable event ID.

## Consequences

- Tamaki gains a social and organizational workplace without losing AO
  identity or local-first continuity.
- Human authority becomes easier to exercise through explicit intents,
  approvals, refusals, budgets, and stop requests.
- Tamaki remains bounded rather than unlimited. Those bounds are its
  capability, consent, resource, repository, and finite-life physiology—not
  accidental ownership by the UI.
- Cloud Itonami projections are disposable read models; they are not organism
  memory or proof of an executed effect.
