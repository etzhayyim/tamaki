# ADR-0008: Tamaki actors also run as Cloud Itonami Bots in the etzhayyim organization

**Status:** accepted — 2026-08-23. Extends ADR-0001 (an Actor is a durable
role; `codex` / `claude` / `grok` are runner profiles) and ADR-0007 (the Cloud
Itonami workplace boundary). Changes one disposition in `maintenance`.

## Context

The owner asked that the Tamaki actors also run as Cloud Itonami Bots, under a
profile, as Bots of the etzhayyim organization. Cloud Itonami already has the
machinery: `network-awai/loop-yakuwari` projects a business's roles as a
workforce catalog, Cloud Itonami provisions the catalog as resident Bots, and
its tick drives each Bot one bounded step per cadence (cloud-itonami-app
ADR-0056). What it did not have was a business that IS this repository's
actors, a way for a business to say which organization it belongs to, or a way
for a CLI session to act in that organization. Those three landed in
loop-yakuwari and cloud-itonami-app on the same day; this ADR records Tamaki's
side of it.

Two measurements made while doing so belong here rather than there.

**Only the codex runner executes.** `kotoba.tamaki.adapters/local-command`
routes every runner except codex through `kotoba-code` with the profile's
model id; `bin/kotoba-code` sends a positional goal to its JVM main, which
understands OpenRouter ids and `murakumo:` and nothing else. `"claude-zai:"`
and `"grok:"` arrive there as literal model ids. The durable event log holds
715 such invocations. For those runners, "also runs as a Bot" is not a second
runtime beside a working first one.

**The generated worktrees were holding the superproject's index lock.** The
repository moved from `orgs/kotoba-lang/tamaki` to `orgs/etzhayyim/tamaki`;
157 `.git/worktrees/*` entries came along, the 106 worktree directories under
`orgs/kotoba-lang/` did not, and 14 of them had lost their `.git` file.
`maintenance/inspect-run` guarded against `.git` being a *directory* (an
independent repository) and not against it being *absent*, so the 60-second
cleanup lane ran `git status --untracked-files=all` in those 14 — and git,
asked about a path with no `.git`, walks up and answers for the first
repository it finds: the 4,000-project superproject. Each such status held
`com-junkawasaki/.git/index.lock` for minutes, every minute, and every other
session on the machine that touched the superproject waited on it. A stub
`orgs/kotoba-lang/tamaki/` (an empty checkout whose origin is the superproject
itself) is what keeps `generated-worktree?` true for those paths.

## Decision

1. **Six actors are projected as Bots.** loop-yakuwari's
   `yakuwari/etzhayyim.edn` mirrors `:revenue/growth-supervisor`,
   `:tamaki/loop-gardener`, `:result/evidence-evaluator`,
   `:bridge/radicle-github`, `:toshokan/maturity-curator` and
   `:yabai/phishing-watch-curator` — objective verbatim, `:actor/hil-policy`
   as `:yakuwari/capabilities`, `:tamaki/actor` and `:tamaki/spec` naming the
   file here. Its parity test fails when a mirror drifts from the ActorSpec
   without a stated reason, when an actor is neither projected nor declined,
   or when this checkout cannot be found. **The ActorSpecs under `actors/`
   remain the source of truth**; the registry is a mirror with a gate.

2. **Two are declined, by name.** `:tamaki/family-representative` (its only
   runner is `:deterministic`) and `:tamaki/storage-curator` (it deletes).
   `actors/revenue-targets.edn` is a threshold file, not an actor.

3. **The Bot runs under the `:tamaki-resident` profile** — the fleet's own
   inference (`murakumo` / `murakumo-main`), the same provider as every other
   workforce Bot, declared under its own name so that moving the six is one
   line. A profile says how a role runs, never what it may do: a Bot's grant
   is Cloud Itonami's (one admitted repository, every write held for
   approval), narrower than any ActorSpec here.

4. **The Bots live in the etzhayyim organization tenant**, because the
   business names it (`:business/organization "etzhayyim"`) and Cloud Itonami
   now provisions a business that names an organization only into that
   organization's tenant (cloud-itonami-app ADR-0071).

5. **`maintenance/inspect-run` never asks git about a directory with no
   `.git` entry.** Such a directory is `:preserve` with reason
   `:not-a-worktree`. Nothing here can say what it is, and asking git would
   produce an answer about some other repository. The existing dirty and
   conflict tests now give their fixtures a worktree `.git` file and assert
   the reason, so they cannot pass through the new branch by accident.

## What this does not decide

- Whether Tamaki's own supervisor keeps running the same six. It does today.
  Two runtimes on one ActorSpec is acceptable while the Bot side is new and
  the codex runner is the only one here that executes; retiring one is a
  later decision with the SLO score in hand.
- The `yabai` ActorSpec still names `orgs/etzhayyim/com-etzhayyim-yabai`, a
  checkout west no longer carries (`cloud-itonami/yabai` is the registered
  path). The projection points at the registered path; the ActorSpec is left
  as it is until the supervisor's checkout story for it is decided.
- The stub `orgs/kotoba-lang/tamaki/` and the 106 orphaned worktree
  directories. Removing them is a superproject cleanup, not a Tamaki change;
  this ADR stops the damage they do from here, and names them so the cleanup
  can find them.

## Verification

- `kotoba.tamaki.maintenance-test`: a directory with no `.git` is preserved
  as `:not-a-worktree` and no git command is issued (the process seam is
  armed); without the guard the test fails on both assertions. Dirty and
  conflict fixtures assert `:dirty-worktree` / `:conflict-inspection-failed`.
- Full suite: 287 tests, 878 assertions, 0 failures.
- loop-yakuwari: `check: OK — businesses 14 | roles 98`; parity test mutated
  four ways, exit 1 each.
