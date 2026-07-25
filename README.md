# tamaki 環

`tamaki` is the single CLI for Kotoba's durable agent execution stack. It does
not reimplement the existing runtimes; it gives them one `AgentRun` identity,
append-only event history, state machine, and operator surface.

```text
manimani / itonami
        │ submit AgentRun
        ▼
      tamaki ───────▶ kotoba-fleet lease/governor
        │                     │
        │                     ▼
        │                 murakumo node
        │                     │
        └─────────────────────▼
                          kotoba-code
                               │
                               ▼
                    checkpoint/artifact/receipt
```

## Commands

```sh
bin/tamaki submit "make the tests pass" \
  --project /absolute/path/to/repo --execute

bin/tamaki submit "add the requested helper and tests" \
  --mode fleet \
  --repo kotoba-lang/kotoba-delta \
  --pin c8c405abda731e5889aff3ae2a18ac208b375bb5 \
  --node auto \
  --requires git,nbb

bin/tamaki run <run-id>
bin/tamaki status
bin/tamaki status <run-id>
bin/tamaki resume <run-id>
bin/tamaki agents
bin/tamaki nodes --nodes asher,naphtali --requires git,nbb
bin/tamaki tick --work-dir work --max 3 --nodes asher,naphtali
bin/tamaki infer probe
bin/tamaki infer plan qwen-agentworld-35b-a3b
bin/tamaki murakumo status
bin/tamaki doctor
bin/tamaki contract
```

`submit` is safe by default: it only appends a queued run. Add `--execute` or
invoke `run` separately to cross the execution boundary.

`nodes` and `tick` delegate to `kotoba-fleet`; `infer` and `murakumo` delegate
to the existing Murakumo operators. Their remaining arguments pass through
unchanged, so Tamaki stays one operator entry point without forking those
projects' command contracts.

## Ownership

- `tamaki`: AgentRun contract, run tree, lifecycle and adapters.
- `kotoba-code`: model/tool ReAct loop, test gate, rollback and checkpoint.
- `kotoba-fleet`: distributed lease, capability placement, proposal/governor.
- `murakumo`: Tailscale node transport, reconciliation and inference.
- `scheduler`: portable bounded tick semantics.
- `manimani` / `itonami`: work discovery, policy, approval and business effects.
- Kotobase Datom: shared durable event/checkpoint backend.

The default adapter persists an append-only local `.tamaki/events.edn`.
For one shared run tree across the Mac fleet:

```sh
export TAMAKI_STORE=kotobase
export KOTOBA_URL=https://kotobase.net
export KOTOBA_GRAPH=<shared-graph-cid>
export KOTOBA_TOKEN=<scoped-bearer-token>

bin/tamaki submit "inspect the failing build" --project /path/to/repo
bin/tamaki status
bin/tamaki agents
```

Every machine using the same graph sees the same runs. `TAMAKI_STORE=dual`
makes Kotobase the commit point and appends a local audit/cache copy only after
the shared write succeeds. Remote failure is fail-closed; Tamaki never invents
a local-only event that the rest of the fleet cannot observe.

## Verify

```sh
bb test
bin/tamaki doctor
```
