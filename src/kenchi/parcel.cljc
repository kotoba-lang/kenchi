(ns kenchi.parcel
  "ParcelActor — one parcel/locale = one supervised actor, expressed as a
  langgraph-clj StateGraph. Sources are sealed upstream; this actor only
  FUSES what it is given and routes the result through the ProvenanceGovernor
  before anything is published.

  One graph run = one valuation tick
  (gather → resolve → fuse → govern → cleared|mrv → publish → END).
  Bounded and auditable — not an unbounded loop. The SourceOrchestrator
  invokes a tick per refresh (see `kenchi.sim`).

  Human-in-the-loop = compliance review: `interrupt-before #{:mrv}` pauses the
  actor whenever it is about to DECLINE to publish (withhold / wide band), so
  the ComplianceActor is notified before a non-publication is recorded —
  exactly like langgraph-clj's approval pattern, repurposed for governance."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [kenchi.fusion :as fusion]
            [kenchi.governor :as gov]))

(defn- cleared-record
  "A publishable valuation record (the `com.junkawasaki.kenchi.valuation`
  lexicon shape). Carries its provenance receipts and license stamp."
  [parcel verdict]
  (let [{:keys [point-usd-micros ci-lo ci-hi n-independent asof-max-age-days
                provenance]} (:estimate verdict)]
    {:parcel          (:id parcel)
     :h3              (:h3 parcel)
     :kind            :valuation
     :value-usd-micros point-usd-micros
     :ci-lo-usd-micros ci-lo
     :ci-hi-usd-micros ci-hi
     :n-sources        n-independent
     :max-age-days     asof-max-age-days
     :license          (:license verdict)
     :provenance       (mapv (juxt :source :kind :license) provenance)}))

(defn build
  "Compiles a ParcelActor graph.
  opts: {:checkpointer cp :gate {:min-comps n :min-authorities n}}."
  [& [{:keys [checkpointer gate]
       :or   {checkpointer (cp/mem-checkpointer)
              gate         {}}}]]
  (-> (g/state-graph
       {:channels
        {:observations       {:default nil}   ; injected by SourceOrchestrator
         :parcel             {:default nil}    ; injected (which place)
         :estimate           {:default nil}
         :provenance-verdict {:default nil}
         :record             {:default nil}    ; final published OR mrv record
         :audit              {:reducer into :default []}}})

      ;; 1. Gather — observations arrive via input; passthrough.
      (g/add-node :gather (fn [s] s))

      ;; 2. Resolve — entity-resolve observations onto one canonical parcel.
      ;;    (mock: parcel injected; real impl matches H3/cadastre ids.)
      (g/add-node :resolve (fn [s] s))

      ;; 3. Fuse — the ValuationEngine ensemble (robust, CI-bearing).
      (g/add-node :fuse
        (fn [{:keys [observations]}]
          {:estimate (fusion/estimate observations)}))

      ;; 4. Govern — INDEPENDENT censor: outliers, N-gate, license, staleness.
      (g/add-node :govern
        (fn [{:keys [observations]}]
          (let [v (gov/check observations gate)]
            {:provenance-verdict v
             :audit [{:t :govern
                      :ok? (:ok? v) :license (:license v)
                      :n-comps (:n-comps v) :n-authorities (:n-authorities v)
                      :violations (:violations v)
                      :outliers (mapv :source (:outliers v))}]})))

      ;; 5a. Cleared — build the publishable point record.
      (g/add-node :cleared
        (fn [{:keys [parcel provenance-verdict]}]
          {:record (cleared-record parcel provenance-verdict)
           :audit  [{:t :cleared :license (:license provenance-verdict)}]}))

      ;; 5b. MRV — declined to publish a point; record a band / insufficient.
      ;;     Paused on by interrupt-before (ComplianceActor reviews declines).
      (g/add-node :mrv
        (fn [{:keys [parcel provenance-verdict]}]
          {:record (gov/mrv-record parcel provenance-verdict)
           :audit  [{:t :mrv :violations (:violations provenance-verdict)}]}))

      ;; 6. Publish — emit the record to PDS/Aozora/Datom (mock: audit only).
      (g/add-node :publish
        (fn [{:keys [record]}]
          {:audit [{:t :publish :kind (:kind record)
                    :license (:license record)}]}))

      (g/set-entry-point :gather)
      (g/add-edge :gather  :resolve)
      (g/add-edge :resolve :fuse)
      (g/add-edge :fuse    :govern)

      ;; Cleared to publish a point, else fall back to MRV — the publish gate.
      (g/add-conditional-edges :govern
        (fn [{:keys [provenance-verdict]}]
          (if (:ok? provenance-verdict) :cleared :mrv)))
      (g/add-edge :cleared :publish)
      (g/add-edge :mrv     :publish)
      (g/set-finish-point :publish)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:mrv}})))
