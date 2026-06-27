;; valuation_query.clj — KOTOBA Mesh component (Clojure / kotoba-clj).
;;
;; HTTP-triggered. The manifest binds this to route "/kenchi/valuation"; an HTTP
;; GET to `/mesh/http/kenchi/valuation?parcel=<id>` on a hosting node invokes the
;; `on-http` export with the request bytes, and its output becomes the response.
;;
;; It SERVES already-cleared, license-stamped valuation facts that the off-mesh
;; ParcelActor wrote to the Datom log (kqe). It never fetches a source or fuses
;; on-mesh — that keeps ToS-restricted inputs Murakumo-only and this component
;; pure-read over content-addressed facts.
;;
;; host-imports used:  kqe-assert! / kqe-query  → kotoba:kais/kqe
(ns valuation-query)

(defn- parcel-of [req]
  ;; req bytes → parcel id. Accepts "parcel=<id>" (query/body); falls back to raw.
  (let [s (str req)]
    (if-let [m (re-find #"parcel=([^&\s]+)" s)] (second m) s)))

;; generic invoke / placement probe
(defn run [_ctx]
  (kqe-query "valuation(?p,?v,?lic) :- kenchi-valuation(?p,?v,?lic)."))

;; HTTP trigger: (request-bytes) → latest cleared valuation for the parcel.
;; Datalog over the kenchi-valuation relation the off-mesh actor asserts:
;;   kenchi-valuation(parcel, valueUsd, license, ciLo, ciHi, nSources, asof)
(defn on-http [req]
  (let [parcel (parcel-of req)]
    (kqe-assert! "g" "kenchi-query" "served" parcel)   ; audit the read
    (kqe-query
     (str "answer(?v,?lic,?lo,?hi,?n,?asof) :- "
          "kenchi-valuation(\"" parcel "\",?v,?lic,?lo,?hi,?n,?asof)."))))
