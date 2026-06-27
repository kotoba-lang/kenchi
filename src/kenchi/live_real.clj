(ns kenchi.live-real
  "LIVE NETWORK demo — actually calls HM Land Registry Price Paid (open data,
  no API key) and runs the REAL ingest adapter → fusion → governor path on the
  response. This hits the public internet.

    clojure -M:live-real             ; default postcode
    clojure -M:live-real \"SW1A 1AA\"  ; any UK postcode

  Proves the production swap: same adapter/fusion/governor code the offline
  demos use, with `kenchi.http/caps` (real java.net.http + data.json) injected
  instead of fixtures."
  (:require [kenchi.http :as http]
            [kenchi.ingest :as ingest]
            [kenchi.fusion :as fusion]
            [kenchi.governor :as gov]))

(defn- sparql [postcode]
  (str "PREFIX lrppi: <http://landregistry.data.gov.uk/def/ppi/>\n"
       "PREFIX lrcommon: <http://landregistry.data.gov.uk/def/common/>\n"
       "SELECT ?amount ?date WHERE {\n"
       "  ?addr lrcommon:postcode \"" postcode "\" .\n"
       "  ?t lrppi:propertyAddress ?addr ;\n"
       "     lrppi:pricePaid ?amount ; lrppi:transactionDate ?date .\n"
       "} ORDER BY DESC(?date) LIMIT 8"))

(defn- gbp [usd-micros] (when usd-micros (format "$%.0fk" (/ (double usd-micros) 1e9))))

(defn -main [& [postcode]]
  (let [pc   (or postcode "PL6 8RU")
        caps (http/caps)
        obs  (ingest/fetch-source caps :uk-landreg {:sparql (sparql pc) :now-year 2026})]
    (println "── LIVE: HM Land Registry Price Paid (real network) ──")
    (println "   postcode" pc "→" (count obs) "real recorded sales")
    (doseq [o (take 8 obs)]
      (println (format "   £%,d  %dd old  [%s]"
                       (:value o) (:age-days o) (name (:kind o)))))
    (if (empty? obs)
      (println "\n   (no sales for that postcode — try another, e.g. \"SW1A 1AA\")")
      (let [est (fusion/estimate obs)
            v   (gov/check obs 3)]
        (println "\n── fuse → govern ──")
        (println "   fused point" (gbp (:point-usd-micros est))
                 " CI" (gbp (:ci-lo est)) "–" (gbp (:ci-hi est))
                 " n-independent" (:n-independent est))
        (println "   governor: publishable?" (:ok? v)
                 " license=" (name (:license v))
                 (when (seq (:violations v)) (str " violations=" (:violations v))))))
    (println "\ndone.")))
