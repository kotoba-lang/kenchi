(ns kenchi.live
  "End-to-end LIVE-wiring demo (offline via fixtures): real source adapters →
  ParcelActor fuse/govern → PDS putRecord (dry-run) → flywheel recalibration.

  This is the production pipeline with the network/credential/clock edges
  stubbed by injection (fixtures, dry-run, a passed-in timestamp) — swap
  fixture-caps→a real http client, dry-run?→a session token, and the demo
  timestamp→(java.time/now) and it talks to live APIs and a real PDS.

  Run: clojure -M:dev:live"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [langgraph.graph :as g]
            [kenchi.ingest :as ingest]
            [kenchi.parcel :as parcel]
            [kenchi.publish :as publish]
            [kenchi.flywheel :as flywheel]
            [kenchi.fusion :as fusion]
            [kenchi.sources :as sources]))

(defn- line [& xs] (println (apply str xs)))
(defn- m$ [usd-micros] (when usd-micros (format "$%.0fk" (/ (double usd-micros) 1e9))))

(def ^:private demo-now "2026-06-27T00:00:00Z")

(defn- load-fixture [src]
  (edn/read-string (slurp (io/resource (str "fixtures/" (name src) ".edn")))))

(defn -main [& _]
  ;; ── 1. INGEST: call real adapters (offline fixtures) and normalize ──────────
  (let [fixtures {:jp-registry (load-fixture :jp-registry)
                  :uk-landreg  (load-fixture :uk-landreg)
                  :bis         (load-fixture :bis)}
        caps     (ingest/fixture-caps fixtures)
        ;; SourceOrchestrator fan-out: registry sales (authority MLIT) + BIS
        ;; national index (authority BIS) = two independent authorities.
        obs      (ingest/fetch-observations
                  caps
                  [[:jp-registry {:year 2024 :area "13" :city "13113" :now-year 2026}]
                   [:bis         {:bis-key "Q.JP.N.628"}]])]
    (line "── 1. INGEST (real adapters, offline fixtures) ──")
    (doseq [o obs]
      (line "   " (sources/provenance-line o)))

    ;; ── 2. FUSE + GOVERN via the ParcelActor StateGraph ───────────────────────
    (line "\n── 2. ParcelActor fuse → govern ──")
    (let [actor (parcel/build)                   ; default gate: ≥3 comps, ≥2 authorities
          res   (g/run* actor {:observations obs :parcel sources/demo-parcel}
                        {:thread-id "live/jp"})
          res   (if (= :interrupted (:status res))
                  (g/run* actor nil {:thread-id "live/jp"}) res)
          rec   (get-in res [:state :record])]
      (line "   record: " (:kind rec)
            "  " (m$ (:value-usd-micros rec))
            "  CI " (m$ (:ci-lo-usd-micros rec)) "–" (m$ (:ci-hi-usd-micros rec))
            "  license=" (name (or (:license rec) :n/a)))

      ;; ── 3. PUBLISH to the PDS (dry-run; no token) ──────────────────────────
      (line "\n── 3. PublishActor → com.atproto.repo.putRecord (dry-run) ──")
      (let [conn (publish/pds-conn "https://pds.junkawasaki.com"
                                   "did:web:com-junkawasaki.github.io:kenchi-actor")
            pcaps {:json-write pr-str :json-read identity
                   :http-fn (fn [_] {:status 200 :body {}})}
            out  (publish/put-record! pcaps conn rec demo-now {:dry-run? true})]
        (line "   " (name (:status out)) " → " (:uri out))
        (line "   record[valueUsd]=" (get (:record out) "valueUsd")
              "  [license]=" (get (:record out) "license")
              "  [nSources]=" (get (:record out) "nSources")))))

  ;; ── 4. FLYWHEEL: backtest vs realized sales, recalibrate, re-backtest ───────
  (line "\n── 4. ModelFlywheelActor: backtest vs realized sales → recalibrate ──")
  (let [;; Parcels whose true sale is now known, with the obs available before it.
        cases [{:realized-usd-micros (long (* 99000000 0.0064 1e6))
                :observations [{:source :jp-registry :kind :sale :value 99000000 :currency :jpy :age-days 90 :confidence 0.95}
                               {:source :osm-footprint :kind :avm :value 70000000 :currency :jpy :age-days 10 :confidence 0.45}
                               {:source :scraper-x :kind :list :value 180000000 :currency :jpy :age-days 5 :confidence 0.2}]}
               {:realized-usd-micros (long (* 102000000 0.0064 1e6))
                :observations [{:source :jp-registry :kind :sale :value 101000000 :currency :jpy :age-days 60 :confidence 0.95}
                               {:source :osm-footprint :kind :avm :value 75000000 :currency :jpy :age-days 8 :confidence 0.45}
                               {:source :scraper-x :kind :list :value 200000000 :currency :jpy :age-days 3 :confidence 0.2}]}]
        {:keys [before after rel]} (flywheel/improvement fusion/default-reliability cases)]
    (line (format "   median abs %% error:  before %.1f%%  →  after %.1f%%"
                  (* 100 before) (* 100 after)))
    (line "   reliability moved: "
          (format "scraper-x %.2f→%.2f  osm-footprint %.2f→%.2f"
                  (:scraper-x fusion/default-reliability) (:scraper-x rel)
                  (:osm-footprint fusion/default-reliability) (:osm-footprint rel))))
  (line "\ndone."))
