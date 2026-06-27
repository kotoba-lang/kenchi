(ns kenchi.commoncrawl
  "IngestActor — Common Crawl. Pull property ASKING prices at web scale from the
  OPEN crawl instead of hitting portal APIs directly. Two phases:

    1. index-query — CC CDX index API → captures (url, WARC filename+offset+length)
    2. fetch-record (injected) → one gzip'd WARC record → extract the asking price

  WHY this fits kenchi's model exactly:
    - kind :list   — an ASKING price, not a sale → already low kind-prior (0.5),
                     so it adds breadth but never dominates the £ point.
    - license :derived-only — the underlying portal page is ToS-restricted; the
                     crawl is open, but we must NOT republish the raw listing, so
                     the fused number is a derived work (LicenseGovernor enforces).
    - authority    — the portal domain (e.g. rightmove.co.uk), an INDEPENDENT
                     authority from HM Land Registry (sales) and BIS (index),
                     so Common Crawl can be the source that diversifies authority.
    - RENTALS ARE EXCLUDED — '£1,195 pcm' is not a valuation signal.

  I/O injected (ADR-0001): index-query uses :http-fn/:json-read (testable
  offline via fixtures); fetch-record is a passed-in thunk so the binary WARC
  range-fetch (kenchi.http/range-text) is swapped for a fixture in tests."
  (:require [clojure.string :as str]))

(def default-crawl
  "A monthly crawl id (see https://index.commoncrawl.org/collinfo.json). Newer
  crawls exist but coverage of a given URL pattern varies; pick per query."
  "CC-MAIN-2024-51")

;; ─────────────────────────────── 1. CC index ───────────────────────────────

(defn index-query
  "Query the CC CDX index for a URL pattern. Returns a vector of captures
  {:url :timestamp :filename :offset :length :status}. 200-only, deduped by url."
  [{:keys [http-fn json-read]} crawl pattern limit]
  (let [resp (http-fn {:url    (str "https://index.commoncrawl.org/" crawl "-index")
                       :method :get
                       :headers {"accept" "application/x-ndjson"}
                       :query  {"url" pattern "output" "json" "limit" (str limit)}})]
    (if-not (#{200 206} (:status resp))
      []
      (->> (str/split-lines (str (:body resp)))
           (remove str/blank?)
           (map json-read)
           (filter #(= "200" (:status %)))
           (map #(select-keys % [:url :timestamp :filename :offset :length :status]))
           (distinct)
           vec))))

;; ───────────────────────── 2. price extraction (pure) ───────────────────────

(def ^:private rental-re #"(?i)\b(pcm|pw|per\s*(month|week)|/\s*(month|week|mo|wk))\b")

(defn- currency-of [s]
  (cond (str/includes? s "£") :gbp
        (or (str/includes? s "¥") (str/includes? s "円")) :jpy
        (str/includes? s "€") :eur
        :else :usd))

(defn extract-price
  "Pull the SALE asking price from a captured listing record (WARC text).
  Prefers the portal's embedded `primaryPrice`, falls back to a JSON-LD Offer.
  Returns {:value n :currency kw} or nil (no price, or a RENTAL we must skip)."
  [text]
  (let [prim  (second (re-find #"\"primaryPrice\"\s*:\s*\"([^\"]+)\"" text))
        offer (second (re-find #"\"@type\"\s*:\s*\"Offer\"[^}]*?\"price\"\s*:\s*\"?(\d{4,})" text))
        cand  (or prim (when offer (str "£" offer)))]
    (when (and cand (not (re-find rental-re cand)))
      (let [digits (str/replace cand #"[^0-9]" "")]
        (when (>= (count digits) 4)
          {:value (parse-long digits) :currency (currency-of cand)})))))

;; ───────────────────────────── observations ────────────────────────────────

(defn- domain-of [url]
  (some-> url (str/replace #"^https?://" "") (str/replace #"^www\." "")
          (str/split #"/") first keyword))

(defn- ts-age-days [ts now-year]
  (let [yr (some-> ts (subs 0 4) parse-long)]
    (* 365 (max 0 (- now-year (or yr now-year))))))

(defn observe
  "Live-wiring: index-query the pattern, fetch each captured record via
  `fetch-record` (a thunk capture→record-text, default = network binary fetch),
  extract the asking price, and stamp a :list / :derived-only observation whose
  authority is the portal domain. Caps {:http-fn :json-read}; opts
  {:limit :now-year :crawl :fetch-record}."
  [{:keys [http-fn json-read] :as caps} pattern
   {:keys [limit now-year crawl fetch-record] :or {limit 10 now-year 2026}}]
  (let [crawl (or crawl default-crawl)
        caps  {:http-fn http-fn :json-read json-read}]
    (->> (index-query caps crawl pattern limit)
         (keep (fn [cap]
                 (when-let [text (try (fetch-record cap) (catch Throwable _ nil))]
                   (when-let [{:keys [value currency]} (extract-price text)]
                     {:value value :currency currency :kind :list
                      :license :derived-only
                      :source :common-crawl
                      :authority (or (domain-of (:url cap)) :common-crawl)
                      :confidence 0.5
                      :age-days (ts-age-days (:timestamp cap) now-year)}))))
         vec)))
