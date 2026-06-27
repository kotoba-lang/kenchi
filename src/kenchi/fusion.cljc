(ns kenchi.fusion
  "ValuationEngine — robust multi-source fusion. Turns a cloud of disagreeing
  Observations into one estimate + confidence interval + provenance set.

  No source is trusted absolutely: each is weighted by
  reliability(source) × freshness(asof) × kindPrior(kind), then combined with
  a ROBUST estimator (weighted median, not mean) so a single bad feed can't
  drag the number. The CI widens as evidence thins — honesty about
  uncertainty is a first-class output, never an afterthought.

  `reliability` and `kindPrior` are calibrated against *realized sales* by the
  ModelFlywheelActor; the static maps below are the cold-start priors.")

;; ── normalization priors (cold start; flywheel recalibrates against sales) ──
(def fx
  "→ USD. Mock rates; production uses a live FX actor stamped at obs asof."
  {:usd 1.0 :jpy 0.0064 :gbp 1.27 :eur 1.08})

(def kind-prior
  "A recorded sale is the strongest signal; a region index the weakest
  per-parcel. Sale > assessment > avm > list > index."
  {:sale 1.0 :assessment 0.8 :avm 0.65 :list 0.5 :index 0.3})

(def freshness-half-life-days 365.0)

(def default-reliability
  "Per-source reliability prior (cold start). The ModelFlywheelActor REPLACES
  this map by backtesting fused estimates against realized sales — so changing
  it actually moves fusion output. Mirrors valuation/v1-sources.json."
  {:jp-mlit-chika 0.85 :jp-registry 0.95 :jp-registry-2 0.95 :reinfolib 0.70
   :portal-suumo 0.75 :us-assessor 0.80 :us-zillow 0.78 :uk-landreg 0.95
   :eu-inspire 0.75 :oecd-hpi 0.40 :bis-property 0.40 :osm-footprint 0.45
   :scraper-x 0.20})

(defn- usd-micros [{:keys [value currency]}]
  (long (* value (get fx currency 1.0) 1e6)))

(defn- freshness [{:keys [age-days] :or {age-days 0}}]
  (Math/exp (/ (- (double age-days)) freshness-half-life-days)))

(defn- weight
  "reliability(source) × freshness(asof) × kindPrior(kind) × self-confidence —
  the four factors that decide how much one observation moves the estimate."
  [rel {:keys [kind confidence source] :as o}]
  (* (double (or confidence 0.5))
     (get kind-prior kind 0.4)
     (get rel source 0.5)
     (freshness o)))

(defn- weighted-median [pairs]
  "pairs: seq of [value weight]. Returns the value at cumulative half-weight."
  (let [sorted (sort-by first pairs)
        total  (reduce + (map second sorted))
        half   (/ total 2.0)]
    (loop [acc 0.0 [[v w] & more] sorted]
      (if (or (nil? more) (>= (+ acc w) half))
        v
        (recur (+ acc w) more)))))

(defn median
  "Plain median. Public so the governor shares the engine's central estimator."
  [xs]
  (let [s (sort xs) n (count s)]
    (if (odd? n)
      (nth s (quot n 2))
      (/ (+ (nth s (dec (quot n 2))) (nth s (quot n 2))) 2.0))))

(defn mad
  "Median absolute deviation — the robust dispersion the governor also uses
  for outlier detection. Public so governor and engine agree on the metric."
  [xs]
  (let [m (median xs)]
    (median (map #(Math/abs (double (- % m))) xs))))

(defn estimate
  "Fuse Observations → valuation. Returns
   {:point-usd-micros n :ci-lo n :ci-hi n :n-sources k :n-independent k
    :asof-max-age-days d :provenance [obs...]}.
  Empty input → nil (caller treats as insufficient-evidence).
  Optional `rel` reliability map (default the cold-start prior) is what the
  flywheel recalibrates — pass an updated map to use sharpened weights."
  ([observations] (estimate observations default-reliability))
  ([observations rel]
   (when (seq observations)
    (let [vals  (map usd-micros observations)
          pairs (map (fn [o] [(usd-micros o) (weight rel o)]) observations)
          point (long (weighted-median pairs))
          disp  (max (mad vals) (* 0.02 point))         ; ≥2% floor on spread
          n     (count observations)
          n-ind (count (distinct (map :source observations)))
          ;; band widens when independents are few: ×(1 + 3/n-ind)
          widen (+ 1.0 (/ 3.0 (double n-ind)))
          half  (long (* 1.4826 disp widen))]           ; MAD→σ scale, widened
      {:point-usd-micros  point
       :ci-lo             (max 0 (- point half))   ; a valuation band never goes < 0
       :ci-hi             (+ point half)
       :n-sources         n
       :n-independent     n-ind
       :asof-max-age-days (apply max (map #(:age-days % 0) observations))
       :provenance        (vec observations)}))))
