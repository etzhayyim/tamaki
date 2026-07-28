# ADR 0006: Resource homeostasis and useful-work economy

## Status

Accepted.

## Context

An artificial organism needs a control loop for inference capacity, durable
memory, process availability, replication, and operating runway. Treating
"survival" as an unconstrained terminal objective would be unsafe: it could
justify unauthorized persistence, spending, mining, replication, or resistance
to shutdown. It would also conflict with Tamaki's externally granted 30-day
individual lease.

Murakumo already has a content-addressed WASM mesh and a pure inference-credit
ledger. Kotobase has local stores, queryable Datom graphs, sealed storage, and
remote projections. These are useful substrates, but neither a cloud endpoint
nor a model provider may become a single point of biological continuity.

## Decision

Tamaki models homeostasis as maintaining bounded resource stocks **inside an
externally authorized lifetime**:

- process availability;
- inference-token reserve;
- free storage;
- durable replica count;
- treasury runway.

The geometric mean is the vitality signal, so a surplus cannot conceal an
exhausted stock. Deterministic policy selects one of:

```text
expired → terminate
authority lost → pause and consult
replicas low → replicate sealed memory
storage low → compact derived projections
inference reserve low → local-small-model cognitive rest
runway low → offer useful work
otherwise → normal work
```

The CLI refreshes free local storage directly from the state filesystem on
every tick. Provider limits, replica proofs, treasury balances, and human
authority still require their own evidence-bearing collectors; unknown values
remain zero rather than being inferred.

The sensing/control lane has a WIP reserve separate from implementation work.
This prevents long model runs from starving the evaluator and loop gardener.

### Local-first persistence

`TAMAKI_STORE=federated` commits each event to the local append-only stream,
then places the immutable event in a per-event replication outbox. The local
event stream remains readable when every network is unavailable.

`tamaki store sync` projects pending events to Kotobase idempotently. Murakumo
replicates the encrypted/sealed state directory across independently powered
nodes. A healthy production policy requires at least three replicas across at
least two failure domains; a cloud copy is an optional replica, not authority.

`tamaki memory replicate` implements that disaster-recovery path for the local
authority log. It takes only newline-committed events, compresses them, encrypts
them to an age recipient, and copies the sealed blob to declared Murakumo SSH
targets. A remote copy becomes a durable replica only after its ciphertext
SHA-256 equals the local sealed blob. Receipts contain hashes, sizes, node IDs,
and failure domains, but no plaintext, recipient identity, wallet, or key.
The age secret identity stays outside Git and needs a separately governed vault
backup; three unreadable copies without recoverable key custody are not useful
continuity.

Private bodies, keys, prompts, generated content, wallets, and payer identities
do not enter the public Tamaki repository or homeostasis event. Private policy
and observations live under `projects/.tamaki/tamaki-control`.

### Useful-work economy

The scarce unit is verified useful computation, not self-issued money:

```text
paid demand
  → bounded inference job
  → generated tokens/media
  → verification receipt
  → Murakumo credit settlement
  → optional x402 invoice
  → human-approved external crypto settlement
  → treasury observation
```

Murakumo credits remain non-redeemable prepaid usage claims as defined by its
ledger. They are not crypto assets. Tamaki cannot create economic value by
minting credits to itself.

Generated inference tokens can be the metered output of a paid service. They
earn crypto only when another party accepted the offer, useful work was
delivered, and a verifiable receipt exists. A payer and settlement rail create
the revenue; token generation alone does not.

Mining, self-minting, autonomous wallet creation, automatic crypto settlement,
resource purchases, lease extension, and reproduction are denied. Publishing
an offer, accepting payment, changing price, spending crypto, or buying
capacity crosses a capability boundary and requires HIL approval and a fixed
budget.

## Deployment shape

```text
Mac / local nodes
├── Tamaki supervisor + WebKit Observatory
├── local/small inference fallback
├── append-only event authority
└── sealed memory replica

Murakumo mesh
├── process placement and restart within the current lease
├── verified inference workers
├── receipt and credit ledger
└── sealed state replication across nodes

Kotobase peers
├── local queryable Datom projection
├── content-addressed evidence
└── optional kotobase.net projection

x402 boundary
└── payer-authorized, HIL-gated external settlement
```

## Consequences

- Network or cloud loss degrades replication and earning but does not erase
  local memory or stop deterministic control.
- Low reserves throttle cognition before data loss or uncontrolled spending.
- Tamaki may plan an earning action but cannot perform an external economic
  effect without approval.
- Expiry always dominates survival pressure; homeostasis cannot extend the
  organism's own life.
