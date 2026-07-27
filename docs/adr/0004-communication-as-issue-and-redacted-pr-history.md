# ADR 0004: Communication as issue and redacted PR history

## Status

Accepted

## Decision

Every actionable email, message, or telephone call is projected as one
`:issue/type :communication` node in the canonical local EDN topology.
Dependencies use the existing `:issue/blockers` edge, so the same deterministic
ranking and cycle checks apply to code work and human follow-up.

The provider message ID, addresses, phone numbers, credentials, recordings and
body remain in the local transport store. Tamaki stores stable digests,
channel, direction, time, consent state, a deliberately redacted summary and
the resulting decision. Public repositories never receive raw communication.
Communication nodes are always `:issue/visibility :local-private` and
`:issue/projectable? false`. The Radicle projector independently rejects every
communication node even if it is accidentally given a forge projection ID.

When communication leads to source code, its history is joined to the existing
Issue → Source → Patch → Review → Merge graph through a
`:communication/pr-history` receipt. The receipt contains communication issue
IDs and content digests, not bodies or participant identities. This allows an
auditor to verify which interactions informed a PR without publishing them.

Sending email or messages and placing calls remain external effects requiring
the actor's HIL policy. Ingestion and redacted projection may run
autonomously. Recording a call requires the participants' applicable consent;
Tamaki records only consent state, never assumes it.

For email send/reply, approval is content-bound rather than a general
permission. The local review surface displays the sending account, recipients,
subject, complete body, attachment manifest, related issue, and reply context.
The approval receipt contains the SHA-256 digest of that exact draft. A change
to any reviewed field makes the receipt unusable, returning the effect to
review. Only the redacted digest receipt enters Tamaki's event history; the
preview remains in the local-private mail store.

## Consequences

- A reply, approval, unanswered call, or requested decision can block another
  issue without inventing a separate coordination protocol.
- Multiple communications can depend on one another and participate in the
  same acyclic topology query.
- PR review judges source, tests and redacted receipts. It does not inspect or
  reproduce private message content.
- Local deletion policy may remove provider content while retaining a digest
  and outcome receipt.
