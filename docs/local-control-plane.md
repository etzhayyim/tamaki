# Local control plane

Tamaki is a public application. Concrete organizations, repositories, domains,
objectives, actors, loops, budgets, and account references are runtime data and
must not be committed to this repository.

The resident domain supervisor reads private configuration from:

```text
<workspace>/projects/.tamaki/tamaki-control
```

Set `TAMAKI_WORKSPACE` and optionally `TAMAKI_CONTROL_ROOT` to override it.
The resident supervisor writes observations and AgentRun events to Tamaki's
canonical local stream at `<tamaki>/.tamaki` so the portfolio controller and
Observatory consume the same facts. Set `TAMAKI_STATE_DIR` only when an
explicitly isolated test or staging control plane is intended; an isolated
stream does not drive the resident business loop.

The control directory may be an independent private Git repository:

```text
control/
├── actors/
├── organisms/
├── loops/
├── domains.edn
├── services/                 # service policies; no customer or message data
└── .gitignore
```

Credentials, tokens, message bodies, private observations, event streams,
receipts, worktrees, and generated snapshots do not belong in that repository.
ActorSpecs store secret references only. Provider adapters resolve those
references from the OS keychain or another secret broker at execution time.

The public runtime fails closed when a private actor declares Radicle
publication. A local control repository can select GitHub private as the
primary issue and delivery authority without exposing its configuration in the
Tamaki source tree.
