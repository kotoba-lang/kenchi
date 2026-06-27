(ns kenchi.core
  "kenchi-clj — provenance-gated, multi-source real-estate valuation, as a library.

  Give it canonical Observations gathered from many INDEPENDENT authorities and
  it returns a fused, uncertainty-quantified EXTERNAL-MARKET valuation — or it
  REFUSES (a wide MRV band, or insufficient-evidence) when the evidence can't
  defend a point. No single source is trusted; the ProvenanceGovernor is the
  product:

    a point publishes only with ≥3 independent recorded comps AND ≥2 independent
    authorities AND a fresh anchor — recorded sales anchor the price, a region
    index only corroborates, and ToS-restricted inputs publish derived-only.

  Portable .cljc (JVM / SCI / ClojureScript / GraalVM), on the langgraph-clj /
  langchain-clj layering. I/O is INJECTED, so the pure core has no HTTP/JSON
  dependency. Opt-in layers:
    kenchi.ingest / kenchi.http / kenchi.commoncrawl — live ingestion adapters
    kenchi.parcel                                    — a langgraph-clj actor (1 run = 1 valuation)
    kenchi.publish                                   — ATProto PDS record + lexicon
    kenchi.flywheel                                  — backtest vs realized sales → recalibrate

  Quick start:
    (require '[kenchi.core :as kenchi])
    (kenchi/value
      [(kenchi/observation {:authority :hm-land-registry :kind :sale :value 250000 :currency :gbp :age-days 200})
       (kenchi/observation {:authority :hm-land-registry :kind :sale :value 310000 :currency :gbp :age-days 365})
       (kenchi/observation {:authority :hm-land-registry :kind :sale :value 275000 :currency :gbp :age-days 700})
       (kenchi/observation {:authority :bis :kind :index :value 112.4 :currency :index})])
    ;; => {:verdict :published :valuation {:value-usd-micros … :license :open …} :provenance {…}}"
  (:require [kenchi.fusion :as fusion]
            [kenchi.governor :as gov]))

(def price-kinds
  "Observation kinds that carry a per-parcel PRICE LEVEL (anchor the point).
  :index is NOT here — a region index corroborates, never votes the price."
  fusion/price-kinds)

(defn observation
  "Normalize/construct one canonical Observation map. Required: :value :kind
  (:sale|:assessment|:avm|:list|:index) :currency (:gbp|:jpy|:usd|:eur|:index).
  Optional (defaulted): :authority (defaults to :source) :source :license
  (:open|:derived-only|:restricted) :age-days :confidence (0..1)."
  [m]
  (merge {:license :open :confidence 0.5 :age-days 0} m))

(defn value
  "Fuse + govern Observations → a valuation RESULT:
    {:verdict   :published | :withheld-mrv | :insufficient-evidence
     :valuation {…the publishable number + provenance…} | nil   ; only when :published
     :band      {:lo n :hi n} | nil                              ; MRV band (micros) when withheld
     :provenance <full governor verdict — comps, authorities, outliers, violations>}
  opts {:min-comps n :min-authorities n} override the publish gate (defaults 3 / 2)."
  ([observations] (value observations {}))
  ([observations opts]
   (let [v   (gov/check observations opts)
         est (:estimate v)]
     (cond
       (:ok? v)
       {:verdict    :published
        :valuation  {:value-usd-micros (:point-usd-micros est)
                     :ci-lo-usd-micros (:ci-lo est)
                     :ci-hi-usd-micros (:ci-hi est)
                     :n-comps          (:n-comps v)
                     :n-authorities    (:n-authorities v)
                     :max-age-days     (:asof-max-age-days est)
                     :license          (:license v)
                     :provenance       (mapv (juxt :authority :kind :license) (:kept v))}
        :band       nil
        :provenance v}

       est
       {:verdict    :withheld-mrv
        :valuation  nil
        :band       {:lo (:ci-lo est) :hi (:ci-hi est)}
        :provenance v}

       :else
       {:verdict :insufficient-evidence :valuation nil :band nil :provenance v}))))

(defn region-report
  "Aggregate Observations into a region DISTRIBUTION (median + quartiles in
  USD-micros). Publishable even where per-parcel valuation is barred
  (AGGREGATE-ONLY): names no parcel. nil without price comps."
  [observations]
  (fusion/region-aggregate observations))

(defn gate
  "The active publish-gate thresholds (for display / docs)."
  []
  {:min-comps gov/min-comps :min-authorities gov/min-authorities
   :outlier-mad-k gov/outlier-mad-k :max-publish-age-days gov/max-publish-age-days})
