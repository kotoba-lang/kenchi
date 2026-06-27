# kenchi-actor 検地

A **worldwide real-estate valuation actor** that fuses *many disagreeing
sources* into one **provenance-stamped, uncertainty-quantified** value and
**publishes** it — expressed on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop, interrupts,
Datomic/in-mem checkpoints), published over the same ATProto/Aozora +
Murakumo surfaces as [`toritate`](../../etzhayyim/root/20-actors/toritate).

> **検地** (*kenchi*) — Hideyoshi's cadastral land survey that, for the first
> time, measured and ledgered the value of land across an entire realm. This
> actor is that survey for the planet: measure every parcel from every
> available source, reconcile them, and write the result to an open ledger.

> **Why an actor layer at all?** No single source is ground truth. A county
> assessor roll, a Zillow Zestimate, a recorded sale, MLIT 地価公示, an OECD
> index, and an OSM footprint will give you *six different numbers* for the
> same place — at different freshness, coverage, currency, and **license**.
> Any one of them is a **proposal, not a fact**. kenchi seals each source in
> its own node and wraps them with the independent provenance, license,
> fusion, and publish layers a *defensible public number* actually needs.

## The core contract

```
many sources (assessor · portal · sale · index · cadastre · alt-data)
        │  each = one sealed IngestActor, emits a stamped Observation
        ▼
   ┌──────────────┐   estimate + CI   ┌────────────────────┐
   │ ValuationEngine│ ───────────────▶ │ ProvenanceGovernor │  (independent)
   │ (fusion ens.) │   provenance set  │ N-source · license │
   └──────────────┘                   └─────────┬──────────┘
                                  publish ◀──────┴──────▶ withhold
                                     │                      │
                            cleared lexicon record     MRV (widen band /
                            (PDS · Aozora · Datom)      "insufficient evidence")
```

**kenchi never publishes a point value the ProvenanceGovernor can't trace to
≥N independent, license-cleared sources with a stated confidence interval.**
That single invariant is what makes a fused number defensible in public.

## Run

```bash
clojure -M:dev:run     # value one parcel through 3 scenarios (mock feeds)
clojure -M:dev:live    # full pipeline: real adapters → fuse/govern → PDS → flywheel
clojure -M:dev:test    # the provenance + live-wiring contracts as tests
```

`:run` walks one parcel through: rich agreement (6 sources, tight CI → published)
→ thin/stale evidence (2 sources → MRV wide band) → a portal whose ToS forbids
republication (LicenseGovernor → derived-only, raw withheld).

`:live` runs the **production pipeline offline**: real source adapters (served
from recorded fixtures) → `ParcelActor` fuse/govern → `com.atproto.repo.putRecord`
to the PDS (dry-run) → `ModelFlywheelActor` backtest-vs-realized-sales →
reliability recalibration. Swap the injected http-fn / token / clock to go live.

`:live-real` makes **real network calls** to two independent open authorities —
**HM Land Registry** Price Paid (recorded £ sales, the anchor) and **BIS**
Selected Property Prices (national index, corroboration) — fuses, clears the
publish gate, and emits a per-parcel `valuation` plus an aggregate `regionReport`
(both dry-run). Example (`PL6 8RU`): 12 real comps + BIS → publishable, point
≈ $277k, regionReport median ≈ $302k. The £ point comes only from recorded
sales; the index never votes a level — it only diversifies **authority**. The
gate is honest: a point publishes only with **≥3 independent recorded comps**
AND **≥2 independent authorities** AND a fresh anchor; a single authority (even
with many comps) is refused.

## Layout

| File | Actor / role |
|---|---|
| `src/kenchi/sources.cljc` | **IngestActor (mock feeds)** — per-source stamped Observations for the offline demo |
| `src/kenchi/ingest.cljc` | **IngestActor (live wiring)** — real source adapters (MLIT / HM Land Registry / BIS / OECD), authority-stamped, injected HTTP I/O, fixture caps |
| `src/kenchi/commoncrawl.clj` | **IngestActor — Common Crawl** — web-scale portal *asking* prices from the open crawl (CC index → WARC range-fetch → price extract), `:list` / `:derived-only`, rentals excluded |
| `src/kenchi/http.clj` | real `java.net.http` + `data.json` host-caps (incl. gzip WARC range-fetch) — the production swap for fixture-caps |
| `src/kenchi/fusion.cljc` | **ValuationEngine** — robust multi-source ensemble + CI; per-source reliability (flywheel-tuned) |
| `src/kenchi/governor.cljc` | **ProvenanceGovernor / LicenseGovernor** — N-source gate, ToS clear, outlier reject, MRV |
| `src/kenchi/parcel.cljc` | **ParcelActor** — the langgraph-clj StateGraph (1 run = 1 valuation) |
| `src/kenchi/publish.cljc` | **PublishActor** — ATProto PDS `putRecord` client (kotoba-db I/O idiom), wire-level license gate |
| `src/kenchi/flywheel.cljc` | **ModelFlywheelActor** — backtest vs realized sales → recalibrate reliability |
| `src/kenchi/sim.cljc` / `live.clj` | mock demo / live-wiring demo |
| `test/kenchi/*_test.clj` | publish invariant + ingest/publish/flywheel contracts |
| `lexicons/com/junkawasaki/kenchi/valuation.json` | the published record schema (ATProto lexicon) |
| `valuation/v1-sources.json` · `valuation/fixtures/*.edn` | source-registry + reliability priors · offline adapter fixtures |

## Publish surface

Identity `did:web:…:com-junkawasaki-kenchi` + `.well-known/did.json`, ATProto
**PDS** + **Aozora** AppView, lexicon `com.junkawasaki.kenchi.valuation` /
`…regionReport`. Ships as a **kotoba WASM component** on the Murakumo mesh
(`on-tick` refresh · `on-http` query · `kqe-assert!` the valuation to the
node's Datom log). License-restricted inputs run **Murakumo-only** and are
published **derived-only**.

## Status

**Design + runnable scaffold + live-wiring seams.** Architecture in
[`docs/DESIGN.md`](docs/DESIGN.md), decision record in
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md). Fusion,
governor, StateGraph, the PDS `putRecord` client, the source adapters, and the
flywheel are **real and tested** (`:test` = 11 tests / 32 assertions, green).
The network / credential / clock edges are **injected**, so the whole pipeline
runs offline against fixtures and goes live by swapping three injected values:

| Seam | offline (now) | production swap |
|---|---|---|
| `:http-fn` | fixture-caps / dry-run | a real HTTP client (babashka.http-client / hato) |
| PDS auth | no `:token` → dry-run | a session JWT from `com.atproto.server.createSession` |
| clock | a passed-in ISO timestamp | `(java.time/Instant.now)` |

Still to do: real source API keys (MLIT subscription key etc.), a running PDS,
and a real realized-sales feed for the flywheel.
