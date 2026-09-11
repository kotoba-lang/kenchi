(ns kenchi.ingest
  "IngestActor (live wiring) — fetch real source APIs and NORMALIZE each
  response to canonical Observations. One adapter per source.

  I/O is fully INJECTED (same contract as langchain.kotoba-db, ADR-0001) so
  the actor stays zero-dep and portable, and offline runs against fixtures:

    host-caps {:http-fn   (fn [{:keys [url method headers query body]}]
                            => {:status n :body s})
               :json-read (fn [json-or-edn-string] => clj-data, keyword keys)}

  Production injects a real HTTP client (babashka.http-client / hato / JDK
  java.net.http) + a real JSON reader (charred/cheshire). The OFFLINE
  `fixture-caps` below serves recorded EDN bodies so the parse path is
  exercised end-to-end with no network or API keys.

  An adapter NEVER decides truth or publishability — it only emits stamped
  proposals. ProvenanceGovernor and LicenseGovernor censor downstream."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]))

;; ─────────────────────────────── adapters ───────────────────────────────
;; Each adapter declares how to CALL a real source and how to PARSE it into
;; partial observations {:value :currency :age-days :kind :confidence}. The
;; orchestrator stamps :source + :license from the adapter itself.

(defn- yrs-ago->days [years now-year]
  (* 365 (max 0 (- now-year (long years)))))

(defn sdmx-latest
  "Pull the latest observation value from an SDMX-JSON 2.0 message (the shape
  BIS / OECD / Eurostat REST return): data.dataSets[0].series → first series →
  observation at key 0 (BIS/OECD order the most-recent observation first, and
  with lastNObservations=1 it is the only one) → first element. Returns a
  double, or nil if the query matched no data."
  [body]
  (let [series (get-in body [:data :dataSets 0 :series])]
    (when (map? series)
      (let [obs (-> series first val :observations)]
        (when (map? obs)
          (let [latest-k (->> (keys obs) (map name) (map parse-long) (apply min))
                v        (first (get obs (keyword (str latest-k))))]
            (some-> v str parse-double)))))))

(def adapters
  "Live source registry. :request builds the HTTP call; :parse maps the decoded
  body → partial observations; :authority is the independent provider (counted
  for independence, not :source). Endpoints are the real public ones; keys/
  levels come from env via host-caps in production."
  {;; 国交省 不動産情報ライブラリ — 不動産取引価格情報 (recorded transactions).
   ;; Real: GET https://www.reinfolib.mlit.go.jp/ex-api/external/XIT001
   ;;       ?year=YYYY&area=<pref-code>&city=<code>  (header Ocp-Apim-Subscription-Key)
   :jp-registry
   {:license :open :region :jp :authority :mlit
    :request (fn [{:keys [year area city api-key]}]
               {:url    "https://www.reinfolib.mlit.go.jp/ex-api/external/XIT001"
                :method :get
                :headers (cond-> {"accept" "application/json"}
                           api-key (assoc "Ocp-Apim-Subscription-Key" api-key))
                :query  (cond-> {"year" (str year) "area" (str area)}
                          city (assoc "city" (str city)))})
    :parse   (fn [{:keys [data] :as _body} {:keys [now-year]}]
               ;; XIT001 returns {:status .. :data [{:TradePrice "98000000"
               ;;   :Type "宅地(土地と建物)" :Period "2024年第1四半期" ...}]}
               (for [r data
                     :let [price (some-> (:TradePrice r) str (str/replace "," "") parse-long)
                           yr    (some-> (:Period r) (->> (re-find #"(\d{4})")) second parse-long)]
                     :when price]
                 {:value price :currency :jpy :kind :sale :confidence 0.95
                  :age-days (yrs-ago->days (or yr now-year) now-year)}))}

   ;; HM Land Registry — Price Paid (open SPARQL, no key).
   ;; Real: GET https://landregistry.data.gov.uk/landregistry/query?query=<sparql>
   :uk-landreg
   {:license :open :region :uk :authority :hm-land-registry
    :request (fn [{:keys [sparql]}]
               {:url    "https://landregistry.data.gov.uk/landregistry/query"
                :method :get
                :headers {"accept" "application/sparql-results+json"}
                :query  {"query" sparql}})
    :parse   (fn [body {:keys [now-year]}]
               ;; SPARQL JSON: {:results {:bindings [{:amount {:value "750000"}
               ;;                                      :date {:value "2024-03-01"}}]}}
               (for [b (get-in body [:results :bindings])
                     :let [amt (some-> (get-in b [:amount :value]) parse-long)
                           yr  (some-> (get-in b [:date :value]) (subs 0 4) parse-long)]
                     :when amt]
                 {:value amt :currency :gbp :kind :sale :confidence 0.95
                  :age-days (yrs-ago->days (or yr now-year) now-year)}))}

   ;; BIS Selected Property Prices (open SDMX-JSON 2.0, no key) — an INDEPENDENT
   ;; national house-price index. Corroboration only (kind :index): it never
   ;; votes a £ level, it diversifies authority. Real:
   ;;   GET https://stats.bis.org/api/v2/data/dataflow/BIS/WS_SPP/1.0/Q.<cc>.N.628
   :bis
   {:license :open :region :world :authority :bis
    :request (fn [{:keys [bis-key]}]
               {:url    (str "https://stats.bis.org/api/v2/data/dataflow/BIS/WS_SPP/1.0/"
                             (or bis-key "Q.GB.N.628"))
                :method :get
                :headers {"accept" "application/vnd.sdmx.data+json"}
                :query  {"lastNObservations" "1" "format" "jsondata"}})
    :parse   (fn [body _]
               (when-let [idx (sdmx-latest body)]
                 [{:value idx :currency :index :kind :index :confidence 0.45
                   :age-days 60}]))}

   ;; OECD Analytical house prices (open SDMX-JSON, no key) — independent index.
   ;; Real: sdmx.oecd.org/public/rest/data/…@DF_HOUSE_PRICES/<area>.Q.RHPI
   :oecd-hpi
   {:license :open :region :world :authority :oecd
    :request (fn [{:keys [ref-area]}]
               {:url    (str "https://sdmx.oecd.org/public/rest/data/"
                             "OECD.ECO.MPD,DSD_AN_HOUSE_PRICES@DF_HOUSE_PRICES/"
                             (or ref-area "GBR") ".Q.RHPI")
                :method :get
                :headers {"accept" "application/vnd.sdmx.data+json"}
                :query  {"lastNObservations" "1" "format" "jsondata"}})
    :parse   (fn [body _]
               (when-let [idx (sdmx-latest body)]
                 [{:value idx :currency :index :kind :index :confidence 0.40
                   :age-days 90}]))}})

(defn- stamp [source license authority partials]
  (mapv #(assoc % :source source :license license
                  :authority (or authority source)) partials))

(defn fetch-source
  "Call ONE adapter and return its stamped Observations (or [] on error —
  one dead source must never sink the whole valuation)."
  [{:keys [http-fn json-read] :as _host-caps} source params]
  (let [{:keys [request parse license authority]} (get adapters source)]
    (if-not request
      []
      (try
        (let [{:keys [url method headers query]} (request params)
              resp (http-fn {:url url :method (or method :get)
                             :headers headers :query query})
              ok?  (#{200 201} (:status resp))
              body (when ok? (json-read (:body resp)))]
          (if ok?
            (stamp source license authority (parse body params))
            []))
        (catch #?(:clj Throwable :cljs :default) _e [])))))

(defn fetch-observations
  "SourceOrchestrator fan-out: call every requested adapter, collect all
  stamped Observations. `sources` = seq of [source params] pairs. The result
  feeds straight into the ParcelActor :observations channel."
  [host-caps sources]
  (into [] (mapcat (fn [[source params]] (fetch-source host-caps source params)))
        sources))

;; ─────────────────────────── offline fixture caps ───────────────────────────

(def ^:private source-base-url
  {:jp-registry "reinfolib.mlit.go.jp"
   :uk-landreg  "landregistry.data.gov.uk"
   :bis         "stats.bis.org"
   :oecd-hpi    "sdmx.oecd.org"})

(defn fixture-caps
  "Host-caps that serve recorded EDN bodies instead of hitting the network —
  so `clojure -M:dev:run` exercises the real request/parse path offline.
  `fixtures` : {source => decoded-body}. json-read just returns the body
  (fixtures are already decoded EDN, mirroring what a real json-read yields)."
  [fixtures]
  {:http-fn   (fn [{:keys [url]}]
                (let [src (some (fn [[s base]]
                                  (when (str/includes? url base) s))
                                source-base-url)]
                  {:status 200 :body (get fixtures src)}))
   :json-read identity})
