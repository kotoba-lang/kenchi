;; valuation_refresh.clj — KOTOBA Mesh component (Clojure / kotoba-clj).
;;
;; TICK-triggered. The manifest fires `on-tick` every 6h on a hosting node. It
;; sweeps published valuations for staleness and re-announces fresh ones so the
;; content-addressed read plane stays warm — the on-mesh half of the freshness
;; loop (the off-mesh ParcelActor does the actual re-fusion when a sweep flags a
;; parcel as stale).
;;
;; host-imports used:  kqe-assert! / kqe-query  → kotoba:kais/kqe
(ns valuation-refresh)

(defn run [_ctx]
  (kqe-query "stale(?p) :- kenchi-valuation-stale(?p)."))

;; TICK trigger: mark valuations older than the freshness budget as :stale so the
;; off-mesh actor re-fuses them; record the sweep as a Datom for audit/history.
(defn on-tick [_now]
  (kqe-assert! "g" "kenchi-refresh" "swept" "tick")
  ;; flag any valuation whose max contributing observation age exceeds the budget
  (kqe-query
   (str "kenchi-valuation-stale(?p) :- "
        "kenchi-valuation(?p,?v,?lic,?lo,?hi,?n,?asof), "
        "max-age-days(?p,?d), greater(?d,1825).")))
