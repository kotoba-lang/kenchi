# kenchi Actor Design — worldwide real-estate valuation as a contained, provenance-gated node

## 1. Premise: what a valuation *is*, and what every source is missing

There is no single "value of a property." There is a **cloud of
observations** — each from a source with its own coverage, freshness,
currency, methodology bias, and **license** — and a value is whatever you can
*defensibly fuse* out of that cloud.

| Source class | Examples | Nature |
|---|---|---|
| Public assessment | US county assessor rolls · JP 地価公示/路線価 (MLIT/NTA) · cadastres (EU INSPIRE) | authoritative, lagging, tax-biased |
| Recorded transactions | deed records · UK HM Land Registry Price Paid · JP 不動産取引価格情報 | ground-truth-ish, sparse, lumpy |
| Listing portals | Zillow/Redfin · Rightmove · Idealista · SUUMO/AtHome | fresh, **ToS-restricted**, list≠sale |
| AVMs | Zestimate · HouseCanary · CoreLogic | dense, opaque, **derived/licensed** |
| Indices | Case-Shiller · FHFA HPI · OECD · BIS property prices | regional trend, not per-parcel |
| Alt-data | OSM/satellite footprints · rents · mobility | proxy, needs a model |

The decisive constraint, the analogue of "Alpamayo-R1 is not a driving stack":

> **Any single estimate — even a polished AVM — is an *unverified proposal*,
> not a fact, and many of the freshest ones are *not even republishable*.**

So the design problem is **not** "pick the best AVM." It is **"how do we
confine each source inside a trust boundary"** so that the number we publish
to the world is traceable, license-clean, and honest about its uncertainty.
Everything below follows from that.

## 2. Actor topology (supervision tree)

```
KenchiSystem (root supervisor)
│
├── SourceOrchestrator …… source registry, scheduling, rate-limit / ToS budget
│     ├── IngestActor[S] ……… ★ 1 source = 1 actor ★  fetch · normalize · stamp
│     │                        (assessor, MLIT, registry, portal, index, OSM…)
│     ├── LicenseGovernor …… per-source ToS gate: publishable | derived-only | withhold
│     └── FreshnessActor …… staleness tracking, refresh triggers
│
├── ParcelActor[P] ……… ★ 1 parcel / locale = 1 actor; the fusion node ★
│     ├── Resolver ……………… entity-resolve observations → one canonical parcel / H3 cell
│     ├── ValuationEngine …… robust multi-source ensemble → estimate + CI
│     ├── ProvenanceGovernor  INDEPENDENT: ≥N-source rule · license clear · outlier reject
│     └── Ledger …………………… append-only fact log (every input + every decision)
│
├── PublishActor ……… emit cleared records → ATProto PDS · Aozora · lexicon
├── ComplianceActor … license registry · takedown · GDPR/個情 · regional law gate
├── QueryActor ………… read API: value by parcel / H3 / region / time
└── ModelFlywheelActor … backtest fused estimate vs *realized sales* → recalibrate weights
```

Principles:

1. **Each external source is sealed in one IngestActor and never publishes
   directly.** Its output is always a *stamped proposal*, censored by the
   `ProvenanceGovernor` (truth) and `LicenseGovernor` (rights).
2. **Supervision → MRV.** When sources fall below `N` independent, disagree
   beyond tolerance, or go stale, the ParcelActor falls back to a **Minimal
   Reliable Valuation** — publish a *wider band* or `insufficient-evidence`,
   never a false-precision point. (The exact analogue of robotaxi's MRC.)
3. **Everything is a fact.** Every observation and every governor decision is
   appended to a Datom/Datomic log, so *"why is this parcel valued X?"* is a
   Datalog query over the provenance trace — audit, takedown evidence, and
   flywheel recalibration all read the same log.

## 3. ParcelActor internals (the fusion wrapper)

Implemented as a langgraph-clj StateGraph in `src/kenchi/parcel.cljk`.
**One graph run = one valuation tick** — bounded and auditable, not an
unbounded loop.

```
gather → resolve → fuse → govern → decide ─┬─ (≥N · license-clear · CI ok) ─▶ publish → END
                                           └─ (thin / outlier / ToS-block) ─▶ mrv → END
```

Channels: `:observations :parcel :estimate :provenance-verdict :record :audit`.

- **`:observations` are injected** by the SourceOrchestrator — the ParcelActor
  fetches nothing itself; it only *fuses what it is given*.
- **`govern` is a separate system** from the fusion engine. It re-derives the
  independent-source count, applies the license matrix, runs an outlier check,
  and can *reject* the engine's point estimate and substitute the MRV band.
- **`:audit`** accumulates the full provenance set + every reject/withhold.

## 4. ProvenanceGovernor — the layer that earns the right to publish

`src/kenchi/governor.cljk`. The most important component, deliberately built
*independent* of the fusion engine (a different code path may not share its
bugs or its optimism).

| Job | Mechanism |
|---|---|
| **N-source gate** | refuse a *point* estimate backed by `< N` **independent** sources (same-vendor mirrors count once) |
| **License clear** | publish `raw` vs `derived-only` vs `withhold` per source ToS; if any contributing source is derived-only, the *published* record is derived-only |
| **Outlier reject** | robust estimator (median / Huber); quarantine + flag a source disagreeing beyond a MAD threshold |
| **Uncertainty floor** | always attach a CI + `asof`; widen the band as evidence thins → MRV |
| **Provenance** | every published number carries `{source, obs-id, ts, weight}[]` — the receipts |

The **N starts high** (publish only where evidence is rich) and the covered
universe widens as the flywheel proves the fusion calibrated in a region.

## 5. ValuationEngine — robust multi-source fusion

`src/kenchi/fusion.cljk`. Canonical types:

- **Parcel** — `{id, h3, region, ...}` (geo key = H3 cell and/or cadastral id).
- **Observation** — `{value, kind ∈ #{assessment list sale index avm}, currency,
  asof, source, license, confidence}`.

Fusion:

1. **Normalize** currency → USD-micros (FX actor) and as-of → decay weight.
2. **Weight** each obs by `reliability(source) × freshness(asof) × kindPrior(kind)`
   — `sale > assessment > avm > list > index` as a per-parcel prior.
3. **Robust-aggregate** (weighted median / Huber) → `pointUsd`; bootstrap the
   weighted set → `ci[lo,hi]`; carry `nSources`, `asof`, provenance set.
4. **Calibrate** `reliability` and `kindPrior` against *realized sales* via the
   `ModelFlywheelActor` backtest. **This calibration loop is the moat** — the
   same role the data flywheel plays for robotaxi: the system gets more
   trustworthy precisely where the world later proves it right.

## 6. Publish surface (how it lands in this workspace)

- **Identity.** `did:web:…:com-junkawasaki-kenchi` + `.well-known/did.json`,
  `AtprotoPersonalDataServer` + `AozoraAppView` services — mirrors `toritate`.
- **Lexicon.** `com.junkawasaki.kenchi.valuation`:
  `{ parcel, h3, valueUsdMicros, ciLoUsdMicros, ciHiUsdMicros, nSources, asof,
  license, provenance[] }`; aggregated `com.junkawasaki.kenchi.regionReport`
  (median / distribution per H3 region — safe to publish even where per-parcel
  is restricted).
- **Murakumo node.** Ships as a **kotoba WASM component** placed by the lattice
  auction: `on-tick` refresh, `on-http` query, `kqe-assert!` the cleared
  valuation to the node's Datom log. License-restricted inputs run
  **Murakumo-only** (never leave the mesh) and surface **derived-only** —
  exactly toritate's "Murakumo-only inference / encrypted inputs" ceiling.
- **Ceilings.** `DERIVED-ONLY` for ToS-restricted portals · `AGGREGATE-ONLY`
  where per-parcel publication is barred · **no PII** (a parcel is a place, not
  a person) · per-region law gate (GDPR / 個人情報保護法 / local cadastre rules)
  in `ComplianceActor`.

## 7. Why this shape (the parallel to robotaxi)

| robotaxi-actor | kenchi-actor |
|---|---|
| AR1 is a *research advisor*, not a controller | each source is a *proposal*, not a fact |
| SafetyGovernor censors every trajectory | ProvenanceGovernor censors every published value |
| MRC (safe stop) on any doubt | MRV (wide band / withhold) on thin evidence |
| 1 vehicle = 1 actor, AR1 sealed inside | 1 source = 1 IngestActor, sealed; 1 parcel = 1 fusion actor |
| CoC trace → Datalog → flywheel | provenance trace → Datalog → flywheel |
| data flywheel = moat | calibration-vs-realized-sales = moat |

The invariant restated: **a research model may carry a passenger only behind
an independent safety layer; a scraped/licensed estimate may become a public
number only behind an independent provenance layer.** Same architecture, same
trust boundary, different domain.
