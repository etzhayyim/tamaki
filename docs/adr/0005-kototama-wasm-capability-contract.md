# ADR 0005: Kototama Wasm capability contract before execution

## Status

Accepted

## Decision

Tamaki does not translate an actor's business capabilities directly into
runtime authority. An ActorSpec that selects `:kototama-wasm` declares five
separate layers:

1. `:actor/capabilities` — business responsibility owned by the actor.
2. `:execution/realizes` — the explicit subset implemented by this guest.
3. `:imports` — exact `actor:host` functions requested by the Wasm module.
4. `:grants` and `:limits` — authority and resource ceilings.
5. `:effect-policy` — human-control policy for every resulting effect.

The version 1 envelope uses Kototama `actor:host` ABI version 0. Tamaki
validates it before placement and emits a minimal
`:tamaki.capability/*` envelope without objectives, issue content, private
configuration, or credentials. Kototama independently validates that envelope
against its own import surface before it constructs `HostCaps`.

Unknown business-to-import mappings, unknown imports, missing grants, missing
effect policies, unbounded network access, autonomous network writes, and
autonomous secret operations fail closed. Network imports require a non-empty
URL-prefix allowlist. Write and secret imports require their corresponding
runtime-limit opt-ins.

`kotoba-lang/kotoba-core-contracts` is the shared authority for the vocabulary,
business-to-import realization map, effects, decisions, envelope schema, and
supported Kototama ABI surface. `src/kotoba/tamaki/capability.cljc` is only a
compatibility adapter. An ABI version change requires an explicit shared
contract migration; it is not accepted through permissive normalization.

## Initial capability mapping

| Tamaki capability | Kototama imports |
|---|---|
| `:organism/heartbeat` | `clock-monotonic`, `sha256-hex`, `log-write` |
| `:telemetry/read` | `log-read` |
| `:telemetry/write` | `log-write` |
| `:network/fetch` | `http-fetch` |
| `:network/post` | `http-post` |
| `:llm/infer` | `llm-infer` |
| identity generate/sign/verify | corresponding crypto import |
| content digest | `sha256-hex` |
| CBOR/JSON codec | corresponding codec imports |

Generic coding capabilities such as `:implementation`, `:review`, git,
patching, testing, and process execution are deliberately not mapped. The
current `actor:host` ABI cannot realize them. Coding actors therefore remain
hybrid/local workers until bounded capability providers exist; only their
control guest may move to Wasm now.

## Consequences

- Murakumo placement cannot expand authority; it transports an already-bounded
  envelope and observes its lease.
- aiueos may reduce or deny requested grants but cannot silently add grants.
- A compromised Tamaki control plane still meets an independent Kototama
  admission check at the execution boundary.
- Tamaki, Kototama, Fleet, and future tenders cannot drift into separate
  capability vocabularies without changing the shared contract dependency.
- Observatory can display business capability, realized capability, import,
  grant, limit, and effect-policy as different concepts.
