(ns kenchi.flywheel
  "ModelFlywheelActor — the moat. Backtest fused estimates against REALIZED
  sales, then recalibrate per-source reliability so fusion gets sharper
  exactly where the world later proves it right (the valuation analogue of
  robotaxi's data flywheel: every ground-truth event feeds back into the model).

  A backtest case is one parcel where a true sale price is now known, paired
  with the Observations that were available BEFORE that sale:
    {:realized-usd-micros n :observations [obs...]}

  Two outputs:
    1. (backtest rel cases) → median absolute % error of the fused point.
    2. (recalibrate rel cases) → a new reliability map that down-weights
       sources whose values were consistently far from realized prices.
  Run (recalibrate) then (backtest) with the new map to see the error drop."
  (:require [kenchi.fusion :as fusion]))

(defn- usd [{:keys [value currency]}]
  (* value (get fusion/fx currency 1.0) 1e6))

(defn- abs-pct-error [pred actual]
  (when (and pred actual (pos? actual))
    (/ (Math/abs (double (- pred actual))) (double actual))))

;; ───────────────────────────────── backtest ─────────────────────────────────

(defn backtest
  "Median absolute percentage error of the fused POINT vs realized sales,
  under reliability map `rel`. Lower = better calibrated."
  [rel cases]
  (let [errs (keep (fn [{:keys [realized-usd-micros observations]}]
                     (some-> (fusion/estimate observations rel)
                             :point-usd-micros
                             (abs-pct-error realized-usd-micros)))
                   cases)]
    (when (seq errs) (fusion/median errs))))

;; ─────────────────────────────── recalibration ──────────────────────────────

(defn- source-errors
  "For each source, the list of its own |%error| vs realized across all cases —
  how wrong THAT source was, independent of fusion."
  [cases]
  (reduce (fn [m {:keys [realized-usd-micros observations]}]
            (reduce (fn [m o]
                      (if-let [e (abs-pct-error (usd o) realized-usd-micros)]
                        (update m (:source o) (fnil conj []) e)
                        m))
                    m observations))
          {} cases))

(defn recalibrate
  "Return a new reliability map. Each source's reliability moves toward
  `1 - meanError` (clamped to [0.05, 0.99]), blended with its prior by
  `learning-rate` so one batch can't whipsaw a source. Sources unseen in the
  batch keep their prior. This is the weight update the backtest then rewards."
  ([rel cases] (recalibrate rel cases 0.5))
  ([rel cases learning-rate]
   (let [errs (source-errors cases)]
     (reduce (fn [m [src es]]
               (let [mean   (/ (reduce + es) (count es))
                     target (-> (- 1.0 mean) (max 0.05) (min 0.99))
                     prior  (get rel src 0.5)
                     blend  (+ (* (- 1.0 learning-rate) prior)
                               (* learning-rate target))]
                 (assoc m src blend)))
             rel errs))))

(defn improvement
  "Convenience for the demo / KPI gate: backtest error before vs after one
  recalibration pass. Returns {:before e0 :after e1 :rel rel'}."
  [rel cases]
  (let [rel' (recalibrate rel cases)]
    {:before (backtest rel cases)
     :after  (backtest rel' cases)
     :rel    rel'}))
