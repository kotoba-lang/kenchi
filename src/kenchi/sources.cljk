(ns kenchi.sources
  "IngestActor — one external source = one sealed actor. Each fetches, then
  NORMALIZES to a canonical Observation and STAMPS it with provenance +
  license. A source NEVER publishes; it only emits a stamped *proposal*
  that ProvenanceGovernor (truth) and LicenseGovernor (rights) censor.

  CRITICAL: no single source is ground truth. A county assessor, a recorded
  sale, a portal AVM, and an index will disagree on the same parcel at
  different freshness, currency, and republication rights. So this namespace
  treats every feed as a smart-but-partial witness, never an answer.

  In production each `fetch` calls a real source API (assessor roll, MLIT
  地価公示, HM Land Registry, a portal, an index, OSM). Here they're
  deterministic mocks so the actor graph runs offline and the publish
  contract is exercised end-to-end."
  (:require [kotoba.lang.text :as str]))

;; ─────────────────────────── canonical Observation ───────────────────────────
;; {:value     number   — in :currency units
;;  :kind      kw       — #{:sale :assessment :avm :list :index}
;;  :currency  kw       — #{:usd :jpy :gbp :eur}
;;  :age-days  number   — staleness (days since asof)
;;  :source    kw       — vendor/authority id (independence key)
;;  :license   kw       — #{:open :derived-only :restricted}
;;  :confidence number  — source self-reported 0..1}

(defn- obs [source kind value currency age-days license confidence]
  {:source source :kind kind :value value :currency currency
   :age-days age-days :license license :confidence confidence})

;; A Parcel is a place, not a person (no PII). Geo key = H3 cell and/or cadastre id.
(def demo-parcel
  {:id "jp-13113-shibuya-1-2-3" :h3 "8f2f5a0e" :region :jp :currency :jpy})

(defn observe
  "Returns the Observations a SourceOrchestrator would inject for a parcel
  under a given evidence scenario. Real impl fans out to N IngestActors;
  here it returns their collected stamped proposals.

    :rich       — 6 independent sources, fresh, broad agreement
    :thin       — 2 sources, stale (→ MRV: not enough to publish a point)
    :restricted — rich, but a portal AVM is ToS derived-only + one outlier"
  [_parcel scenario]
  (case scenario
    ;; Strong evidence: assessment + two sales + AVM + index + alt-data, all
    ;; open/fresh, tight cluster around ~98M JPY.
    :rich
    [(obs :jp-mlit-chika   :assessment 95000000 :jpy 200 :open 0.85)
     (obs :jp-registry     :sale       99000000 :jpy  90 :open 0.95)
     (obs :jp-registry-2   :sale      101000000 :jpy 140 :open 0.95)
     (obs :reinfolib       :avm        97500000 :jpy  30 :open 0.70)
     (obs :oecd-hpi        :index      98000000 :jpy  60 :open 0.40)
     (obs :osm-footprint   :avm        96000000 :jpy  10 :open 0.45)]

    ;; Thin/stale: only two sources, both old. Not enough independents for a
    ;; defensible point estimate → governor must fall back to MRV.
    :thin
    [(obs :jp-mlit-chika   :assessment 88000000 :jpy 400 :open 0.85)
     (obs :osm-footprint   :avm       110000000 :jpy 120 :open 0.45)]

    ;; Rich, but (a) a portal AVM is republication-restricted (derived-only),
    ;; and (b) one source is a gross outlier the governor must quarantine.
    :restricted
    [(obs :jp-mlit-chika   :assessment 95000000 :jpy 200 :open 0.85)
     (obs :jp-registry     :sale       99000000 :jpy  90 :open 0.95)
     (obs :portal-suumo    :avm        98500000 :jpy   5 :derived-only 0.75)
     (obs :reinfolib       :avm        97500000 :jpy  30 :open 0.70)
     (obs :scraper-x       :list      250000000 :jpy  15 :open 0.20)   ; ← outlier
     (obs :oecd-hpi        :index      98000000 :jpy  60 :open 0.40)]

    []))

(defn provenance-line
  "Human-readable receipt for one observation — the audit trail that makes a
  published number defensible (the analogue of AR1's CoC trace)."
  [{:keys [source kind value currency age-days license]}]
  (str (name source) " " (name kind) " "
       value (str/upper (name currency))
       " (" age-days "d, " (name license) ")"))
