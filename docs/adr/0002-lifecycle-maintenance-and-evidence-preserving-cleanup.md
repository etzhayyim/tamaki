# ADR 0002: Lifecycle maintenance and evidence-preserving Git cleanup

Status: accepted

## Context

Actor reconciliation created a new detached Git worktree per replica but never
collected terminal worktrees. Hundreds accumulated, while dirty changes and
unique commits made indiscriminate deletion unsafe. A legacy keepalive job also
continued restarting an already-completed campaign. Observatory rebuilt the
entire event projection twice every second.

## Decision

Tamaki treats generated worktrees as leased lifecycle resources.

- Automatic removal requires all of: the run is terminal, its grace period has
  elapsed, the worktree is clean, it has no unmerged path, and its HEAD is
  already reachable from the canonical checkout.
- Dirty worktrees, unique commits, index locks, and merge conflicts are
  preserved as evidence. Force removal is forbidden.
- `tamaki maintenance status` previews classifications.
- `tamaki maintenance cleanup --execute` removes only proven-safe candidates
  and appends a durable `:maintenance/completed` receipt.
- The resident supervisor runs cleanup after each actor reconciliation round.
- The loop-gardener receives the latest deterministic maintenance summary and
  may propose recovery work; it cannot delete preserved evidence directly.
- Supervisor, domain supervisor, CLI and Observatory share an explicit
  workspace/state boundary.
- Observatory reads each event frame once and refreshes its expensive snapshot
  every five seconds by default.

## Consequences

Lifecycle storage is bounded without converting cleanup into an implicit loss
of agent output. Conflict resolution becomes an observable work queue. A dirty
tree can remain indefinitely until reviewed, so a later retention/quarantine
policy may archive it, but that policy must preserve a recovery reference.
