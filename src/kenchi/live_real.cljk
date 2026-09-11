(ns kenchi.live-real
  "LIVE NETWORK demo — calls TWO independent open authorities for real and runs
  the full publish path on the result:

    HM Land Registry Price Paid  (recorded £ sales, postcode)   → the £ anchor
    BIS Selected Property Prices (national index, independent)  → corroboration

  Then: fuse → govern → (publishable?) → emit a com.junkawasaki.kenchi.valuation
  record AND a com.junkawasaki.kenchi.regionReport aggregate (both dry-run).

  The gate is honest: a point publishes only with ≥3 independent recorded comps
  AND ≥2 independent authorities AND a fresh anchor. Land Registry's comps +
  BIS corroboration clear it on real data; no source is trusted alone.

    clojure -M:live-real             ; default postcode
    clojure -M:live-real \"SW1A 1AA\"  ; any UK postcode

  This hits the public internet."
  (:require [kenchi.http :as http]
            [kenchi.ingest :as ingest]
            [kenchi.fusion :as fusion]
            [kenchi.governor :as gov]
            [kenchi.publish :as publish]))

(def ^:private demo-now "2026-06-27T00:00:00Z")

(defn- sparql [postcode]
  (str "PREFIX lrppi: <http://landregistry.data.gov.uk/def/ppi/>\n"
       "PREFIX lrcommon: <http://landregistry.data.gov.uk/def/common/>\n"
       "SELECT ?amount ?date WHERE {\n"
       "  ?addr lrcommon:postcode \"" postcode "\" .\n"
       "  ?t lrppi:propertyAddress ?addr ;\n"
       "     lrppi:pricePaid ?amount ; lrppi:transactionDate ?date .\n"
       "} ORDER BY DESC(?date) LIMIT 12"))

(defn- m$ [usd-micros] (when usd-micros (format "$%.0fk" (/ (double usd-micros) 1e9))))

(defn -main [& [postcode]]
  (let [pc   (or postcode "PL6 8RU")
        caps (http/caps)
        ;; ── ingest: two independent authorities, live ──────────────────────
        sales (ingest/fetch-source caps :uk-landreg {:sparql (sparql pc) :now-year 2026})
        bis   (ingest/fetch-source caps :bis {:bis-key "Q.GB.N.628"})
        obs   (into (vec sales) bis)]
    (println "── LIVE ingest (real network) ──")
    (println "   HM Land Registry:" (count sales) "recorded £ sales for" pc)
    (println "   BIS index:" (if (seq bis) (str (:value (first bis)) " (GB property price index)") "—"))
    (println "   authorities:" (mapv name (distinct (map fusion/authority obs)))
             " comps:" (count sales))

    ;; ── fuse → govern ────────────────────────────────────────────────────
    (let [v   (gov/check obs)
          est (:estimate v)]
      (println "\n── fuse → govern ──")
      (println "   fused point" (m$ (:point-usd-micros est))
               " CI" (m$ (:ci-lo est)) "–" (m$ (:ci-hi est)))
      (println "   n-comps" (:n-comps v) "  n-authorities" (:n-authorities v)
               "  publishable?" (:ok? v) "  license=" (name (:license v)))
      (when (seq (:violations v)) (println "   violations:" (:violations v)))

      (let [conn  (publish/pds-conn "https://pds.junkawasaki.com"
                                    "did:web:com-junkawasaki.github.io:kenchi-actor")
            pcaps {:json-write pr-str :json-read identity :http-fn (fn [_] {:status 200 :body {}})}]
        ;; ── publish per-parcel valuation (only if the gate cleared) ─────────
        (when (:ok? v)
          (let [rec {:parcel (str "uk-" pc) :h3 "8a1958"
                     :value-usd-micros (:point-usd-micros est)
                     :ci-lo-usd-micros (:ci-lo est) :ci-hi-usd-micros (:ci-hi est)
                     :n-sources (:n-authorities v) :max-age-days (:asof-max-age-days est)
                     :license (:license v)
                     :provenance (mapv (juxt :source :kind :license) (:kept v))}
                out (publish/put-record! pcaps conn rec demo-now {:dry-run? true})]
            (println "\n── publish: valuation (dry-run) ──")
            (println "   " (name (:status out)) "→" (:uri out)
                     " valueUsd=" (get (:record out) "valueUsd"))))

        ;; ── publish region aggregate (always publishable; AGGREGATE-ONLY) ───
        (let [agg (assoc (fusion/region-aggregate obs)
                         :region (str "UK/" pc) :h3 "8a1958" :license (:license v))
              out (publish/put-region-record! pcaps conn agg demo-now {:dry-run? true})]
          (println "\n── publish: regionReport (dry-run; AGGREGATE-ONLY) ──")
          (println "   " (name (:status out)) "→" (:uri out))
          (println "   median=" (get (:record out) "medianUsd")
                   " p25=" (get (:record out) "p25Usd")
                   " p75=" (get (:record out) "p75Usd")
                   " n=" (get (:record out) "nComps")))))
    (println "\ndone.")))
