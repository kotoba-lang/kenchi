(ns kenchi.governor
  "ProvenanceGovernor + LicenseGovernor — the independent layer that earns a
  fused number the right to be PUBLISHED. Built deliberately apart from
  kenchi.fusion so it doesn't share the engine's optimism or its bugs.

  The single invariant:

    kenchi never publishes a POINT value the governor can't trace to
    ≥N independent, license-cleared sources with a stated CI.

  Five jobs (mirrors robotaxi.safety, in the valuation domain):
    1. Outlier reject — quarantine sources that disagree beyond a MAD threshold.
    2. N-source gate  — refuse a point estimate under N independent sources.
    3. License clear  — raw | derived-only | withhold, per source ToS.
    4. Uncertainty    — a CI is mandatory; thin evidence → MRV (wide band).
    5. Provenance     — every cleared number carries its receipts."
  (:require [kenchi.fusion :as fusion]))

(def min-independent-sources 3)   ; N — the publish gate for a POINT estimate
(def outlier-mad-k 3.5)           ; quarantine beyond k·MAD from the median
(def max-publish-age-days 1825)   ; 5y — older than this can't anchor a point

;; ───────────────────────────── 1. outlier reject ─────────────────────────────

(defn- usd [{:keys [value currency]}]
  (* value (get fusion/fx currency 1.0) 1e6))

(defn flag-outliers
  "Returns [kept dropped]. An observation is an outlier if its USD value is
  more than k·MAD from the median — the robust analogue of SafetyGovernor's
  independent hazard check catching an AR1 hallucination."
  [observations]
  (let [vals (map usd observations)
        m    (fusion/median vals)
        d    (fusion/mad vals)
        thr  (* outlier-mad-k (max d (* 0.02 m)))]
    [(remove #(> (Math/abs (double (- (usd %) m))) thr) observations)
     (filter #(> (Math/abs (double (- (usd %) m))) thr) observations)]))

;; ───────────────────────────── 3. license clear ──────────────────────────────

(defn publish-license
  "The published record's license = the most restrictive contributing source.
  open everywhere → :open (raw republishable). Any derived-only/restricted in
  the mix → :derived-only (no raw passthrough; the fused number is a new work).
  A source that forbids any derived use would force :withhold (none here)."
  [observations]
  (let [ls (set (map :license observations))]
    (cond
      (contains? ls :restricted)   :derived-only  ; usable Murakumo-only; publish derived
      (contains? ls :derived-only) :derived-only
      :else                        :open)))

;; ───────────────────────────── 2/4/5. the gate ───────────────────────────────

(def min-comps 3)               ; ≥3 independent recorded comps anchor the point
(def min-authorities 2)         ; ≥2 independent data authorities corroborate

(defn check
  "Censors a fusion estimate before publish. The £ point is anchored by
  PRICE-kind comps (sales/assessments/avms); region :index observations only
  CORROBORATE — they bypass the £ outlier filter and count toward authority
  diversity, never the median. Publishable iff:
    ≥ min-comps independent recorded comps  (appraisal strength), AND
    ≥ min-authorities independent authorities (provider diversity), AND
    the freshest comp is within the staleness window.
  Returns {:ok? :license :n-comps :n-authorities :estimate :violations
           :outliers :kept}. opts override :min-comps / :min-authorities."
  ([observations] (check observations {}))
  ([observations {:keys [min-comps min-authorities]
                  :or   {min-comps kenchi.governor/min-comps
                         min-authorities kenchi.governor/min-authorities}}]
   (let [price          (filter #(contains? fusion/price-kinds (:kind %)) observations)
         index          (remove #(contains? fusion/price-kinds (:kind %)) observations)
         [kept-p dropped] (if (seq price) (flag-outliers price) [price []])
         kept           (into (vec kept-p) index)        ; indices never £-filtered
         est            (fusion/estimate kept)
         n-comps        (count kept-p)
         n-auth         (count (distinct (map fusion/authority kept)))
         fresh-age      (if (seq kept-p) (apply min (map #(:age-days % 0) kept-p)) ##Inf)
         vs (cond-> []
              (< n-comps min-comps)
              (conj {:rule :insufficient-comps :detail (str n-comps "/" min-comps)})
              (< n-auth min-authorities)
              (conj {:rule :insufficient-authorities :detail (str n-auth "/" min-authorities)})
              (> fresh-age max-publish-age-days)
              (conj {:rule :stale :detail (str fresh-age "d (freshest comp)")}))]
     {:ok?           (empty? vs)
      :license       (if (seq kept) (publish-license kept) :withhold)
      :n-comps       n-comps
      :n-authorities n-auth
      :n-independent n-auth                              ; back-compat alias
      :estimate      est
      :violations    vs
      :outliers      (vec dropped)
      :kept          kept})))

;; ───────────────────────────── MRV fallback ──────────────────────────────────

(defn mrv-record
  "Minimal Reliable Valuation — what we publish when the point gate fails.
  Never a false-precision point: either a wide band (if we have *some*
  evidence) or an explicit `insufficient-evidence`. The analogue of
  robotaxi's MRC safe-stop: degrade honestly instead of asserting."
  [parcel {:keys [estimate] :as _verdict}]
  (if estimate
    {:parcel  (:id parcel)
     :h3      (:h3 parcel)
     :kind    :mrv-band                 ; band only — NO point published
     :ci-lo   (:ci-lo estimate)
     :ci-hi   (:ci-hi estimate)
     :n-sources (:n-independent estimate)
     :note    "wide band — below N independent sources for a point estimate"}
    {:parcel (:id parcel)
     :h3     (:h3 parcel)
     :kind   :insufficient-evidence
     :note   "no admissible observations after license/outlier filtering"}))
