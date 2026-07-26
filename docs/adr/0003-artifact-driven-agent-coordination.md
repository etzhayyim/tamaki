# ADR 0003: Coordinate actors through source and review artifacts

Status: accepted

## Decision

Tamaki coordinates actors and agents through durable result artifacts, not by
interpreting their free-form conversations.

The canonical result topology is:

```text
Objective → Issue → Source/Commit → Radicle Patch → GitHub PR
          → Independent Review → Merge
```

Radicle remains authoritative. A GitHub PR is a CI, visibility, and secondary
review mirror. Conversation may help exploration or human consultation, but it
cannot advance a delivery state.

Only typed events containing identifiers and evidence—issue ID, commit SHA,
patch ID, PR URL, test result, review verdict, visual evidence, and integration
receipt—enter the result graph. The next actor evaluates the source tree and
review artifact represented by that graph.

## Consequences

- Agent prose cannot impersonate a successful test, review, PR, or merge.
- Actors can be replaced without losing coordination state.
- The Observatory can display actual work products and their provenance.
- Handoffs remain reproducible and auditable across models and accounts.
