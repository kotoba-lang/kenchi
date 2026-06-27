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
            [clojure.string :as str]))

;; ─────────────────────────────── adapters ───────────────────────────────
;; Each adapter declares how to CALL a real source and how to PARSE it into
;; partial observations {:value :currency :age-days :kind :confidence}. The
;; orchestrator stamps :source + :license from the adapter itself.

(defn- yrs-ago->days [years now-year]
  (* 365 (max 0 (- now-year (long years)))))

(def adapters
  "Live source registry. :request builds the HTTP call; :parse maps the decoded
  body → partial observations. Endpoints are the real public ones; keys/levels
  come from env via host-caps in production."
  {;; 国交省 不動産情報ライブラリ — 不動産取引価格情報 (recorded transactions).
   ;; Real: GET https://www.reinfolib.mlit.go.jp/ex-api/external/XIT001
   ;;       ?year=YYYY&area=<pref-code>&city=<code>  (header Ocp-Apim-Subscription-Key)
   :jp-registry
   {:license :open :region :jp
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
   {:license :open :region :uk
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

   ;; OECD analytical house price index (open SDMX-JSON) — region trend, weak
   ;; per-parcel prior. Real: stats.oecd.org / sdmx-json. Parsed as an :index obs.
   :oecd-hpi
   {:license :open :region :world
    :request (fn [{:keys [ref-area]}]
               {:url    "https://sdmx.oecd.org/public/rest/data/OECD.ECO.MPD,DSD_AN_HOUSE_PRICES"
                :method :get
                :headers {"accept" "application/vnd.sdmx.data+json"}
                :query  {"refArea" (str ref-area) "format" "jsondata"}})
    :parse   (fn [{:keys [index-value currency age-days]} _]
               ;; Pre-projected in fixtures: the index → a per-parcel anchor.
               (when index-value
                 [{:value index-value :currency (or currency :usd) :kind :index
                   :confidence 0.40 :age-days (or age-days 90)}]))}})

(defn- stamp [source license partials]
  (mapv #(assoc % :source source :license license) partials))

(defn fetch-source
  "Call ONE adapter and return its stamped Observations (or [] on error —
  one dead source must never sink the whole valuation)."
  [{:keys [http-fn json-read] :as _host-caps} source params]
  (let [{:keys [request parse license]} (get adapters source)]
    (if-not request
      []
      (try
        (let [{:keys [url method headers query]} (request params)
              resp (http-fn {:url url :method (or method :get)
                             :headers headers :query query})
              ok?  (#{200 201} (:status resp))
              body (when ok? (json-read (:body resp)))]
          (if ok?
            (stamp source license (parse body params))
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
