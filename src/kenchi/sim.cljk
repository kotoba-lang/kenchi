(ns kenchi.sim
  "Demo runner: value one parcel under three evidence scenarios that exercise
  the whole publish contract:

    scenario 1  rich        → 6 independent sources agree → POINT published (open)
    scenario 2  thin        → 2 stale sources → governor DECLINES → MRV wide band
                              → ComplianceActor review (interrupt) → resume
    scenario 3  restricted  → portal AVM is derived-only + a gross outlier →
                              outlier quarantined, POINT published DERIVED-ONLY

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [kenchi.parcel :as parcel]
            [kenchi.sources :as sources]))

(defn- line [& xs] (println (apply str xs)))

(defn- ymillions [usd-micros]
  (when usd-micros (format "$%.2fM" (/ (double usd-micros) 1e12))))

(defn- value!
  "Run one valuation tick. If it interrupts for compliance review (a decline
  to publish), the ComplianceActor 'acknowledges' and we resume."
  [actor thread-id parcel scenario]
  (let [obs (sources/observe parcel scenario)
        res (g/run* actor {:observations obs :parcel parcel} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  COMPLIANCE REVIEW — declined to publish a point: "
                (pr-str (get-in res [:state :provenance-verdict :violations])))
          (let [res2 (g/run* actor nil {:thread-id thread-id})
                r    (get-in res2 [:state :record])]
            (line "   ▶  recorded: " (:kind r)
                  "  band " (ymillions (:ci-lo r)) "–" (ymillions (:ci-hi r)))
            res2))
      (let [r (get-in res [:state :record])]
        (line "   ✓  " (:kind r)
              (when (:value-usd-micros r)
                (str "  " (ymillions (:value-usd-micros r))
                     "  CI " (ymillions (:ci-lo-usd-micros r))
                     "–" (ymillions (:ci-hi-usd-micros r))))
              "  license=" (name (or (:license r) :n/a))
              "  n=" (or (:n-sources r) 0))
        res))))

(defn -main [& _]
  (let [actor  (parcel/build)
        parcel sources/demo-parcel]
    (line "── ParcelActor " (:id parcel) " (sources sealed; ProvenanceGovernor active) ──")
    (line "   geo: H3 " (:h3 parcel) "  region " (name (:region parcel)))

    (line "\nscenario 1  rich evidence (6 independent, fresh, agreeing)")
    (value! actor "p/rich" parcel :rich)

    (line "\nscenario 2  thin evidence (2 sources, stale)")
    (value! actor "p/thin" parcel :thin)

    (line "\nscenario 3  restricted+outlier (portal derived-only; one bad scraper)")
    (value! actor "p/restricted" parcel :restricted)

    (line "\n── audit (provenance receipts = the data-flywheel + takedown evidence) ──")
    (let [st (g/get-state actor "p/restricted")]
      (doseq [a (get-in st [:state :audit])]
        (line "  " (pr-str a))))
    (line "\ndone.")))
