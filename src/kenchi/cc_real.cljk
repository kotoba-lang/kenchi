(ns kenchi.cc-real
  "LIVE Common Crawl demo — pulls real property ASKING prices from the open
  crawl (CC index → WARC range-fetch → price extract), then shows them as
  :list / :derived-only observations and folds them with HM Land Registry
  sales + BIS index through the publish gate.

    clojure -M:cc-real                         ; default: rightmove sale listings
    clojure -M:cc-real \"zoopla.co.uk/for-sale/details/*\" 8

  This hits the public internet (index.commoncrawl.org + data.commoncrawl.org)."
  (:require [kenchi.http :as http]
            [kenchi.commoncrawl :as cc]
            [kenchi.ingest :as ingest]
            [kenchi.fusion :as fusion]
            [kenchi.governor :as gov]))

(defn- m$ [u] (when u (format "$%.0fk" (/ (double u) 1e9))))

(defn -main [& [pattern limit]]
  (let [pattern (or pattern "rightmove.co.uk/properties/*")
        limit   (parse-long (or limit "25"))
        caps    (http/caps)
        ;; binary WARC range-fetch is the production fetch-record thunk
        fetch   (fn [{:keys [filename offset length]}]
                  (http/range-text (str "https://data.commoncrawl.org/" filename)
                                   (parse-long offset) (parse-long length)))
        cc-obs  (cc/observe caps pattern {:limit limit :now-year 2026 :fetch-record fetch})]
    (println "── LIVE Common Crawl:" pattern "──")
    (println "   captured listings with a sale asking price:" (count cc-obs)
             " (rentals excluded)")
    (doseq [o cc-obs]
      (println (format "   %s %,d  [%s, %s, %dd]"
                       (name (:currency o)) (:value o) (name (:authority o))
                       (name (:license o)) (:age-days o))))
    (when (seq cc-obs)
      (let [agg (fusion/region-aggregate cc-obs)]
        (println "\n   asking-price distribution (this portal, derived-only):")
        (println "   median" (m$ (:median-usd-micros agg))
                 " p25" (m$ (:p25-usd-micros agg)) " p75" (m$ (:p75-usd-micros agg))
                 " n" (:n-comps agg)))
      ;; Common Crawl's real role: a national BREADTH sample that adds a 3rd
      ;; independent AUTHORITY (the portal) and, being ToS-restricted, forces the
      ;; published record to :derived-only. HM Land Registry recorded sales still
      ;; anchor the £; BIS corroborates. Fold all three.
      (let [sparql (str "PREFIX lrppi: <http://landregistry.data.gov.uk/def/ppi/>\n"
                        "PREFIX lrcommon: <http://landregistry.data.gov.uk/def/common/>\n"
                        "SELECT ?amount ?date WHERE { ?addr lrcommon:postcode \"PL6 8RU\" ."
                        " ?t lrppi:propertyAddress ?addr ; lrppi:pricePaid ?amount ;"
                        " lrppi:transactionDate ?date } ORDER BY DESC(?date) LIMIT 12")
            sales (ingest/fetch-source caps :uk-landreg {:sparql sparql :now-year 2026})
            bis   (ingest/fetch-source caps :bis {:bis-key "Q.GB.N.628"})
            obs   (concat (vec sales) cc-obs bis)
            v     (gov/check obs)]
        (println "\n── fold: HM Land Registry (anchor) + Common Crawl + BIS → govern ──")
        (println "   authorities:" (mapv name (distinct (map fusion/authority obs)))
                 " comps:" (:n-comps v))
        (println "   point" (m$ (:point-usd-micros (:estimate v)))
                 " publishable?" (:ok? v) " license=" (name (:license v))
                 (if (seq (:violations v)) (str " " (:violations v)) ""))
        (println "   → Common Crawl made the record DERIVED-ONLY and added authority"
                 (str "(" (count (distinct (map fusion/authority obs))) " total)."))))
    (println "\ndone.")))
