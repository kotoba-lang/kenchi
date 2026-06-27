(ns kenchi.publish
  "PublishActor (live wiring) — write a governor-CLEARED valuation to an
  ATProto PDS as a `com.junkawasaki.kenchi.valuation` record (and, on the
  Murakumo mesh, kqe-assert! the same fact to the node's Datom log).

  I/O fully INJECTED, same contract as langchain.kotoba-db (ADR-0001):
    host-caps {:http-fn    (fn [{:keys [url method headers body]}]
                             => {:status n :body s})
               :json-write (fn [clj-data] => json-string)
               :json-read  (fn [json-string] => clj-data, keyword keys)}

  The ONE invariant this module enforces at the wire: it refuses to PUT a
  record whose license stamp says it cannot leave (`:withhold`), and it
  publishes ToS-restricted inputs only as a derived record — never raw."
  (:require [clojure.string :as str]))

(def collection "com.junkawasaki.kenchi.valuation")
(def region-collection "com.junkawasaki.kenchi.regionReport")

;; ─────────────────────────── connection (PDS) ───────────────────────────

(defn pds-conn
  "Creates a PDS connection map. State lives on the server.
   url  – PDS base, e.g. \"https://pds.junkawasaki.com\"
   did  – the repo DID, e.g. \"did:web:com-junkawasaki.github.io:kenchi-actor\"
   opts – :token Bearer access JWT (from com.atproto.server.createSession)"
  ([url did] (pds-conn url did {}))
  ([url did {:keys [token]}]
   (cond-> {:pds/url url :pds/did did}
     token (assoc :pds/token token))))

(defn- xrpc-url [conn nsid] (str (:pds/url conn) "/xrpc/" nsid))

(defn- headers [conn]
  (cond-> {"content-type" "application/json" "accept" "application/json"}
    (:pds/token conn) (assoc "authorization" (str "Bearer " (:pds/token conn)))))

;; ─────────────────────────── record construction ────────────────────────

(defn ->record
  "Map a cleared ParcelActor record (kenchi.parcel/cleared-record shape) to
  the on-wire lexicon record. micros→USD as a string-decimal so no precision
  is lost on the wire. `at` is an ISO-8601 timestamp (injected; the runtime
  has no clock)."
  [rec at]
  (let [usd (fn [m] (when m (format "%.2f" (/ (double m) 1e6))))]
    {"$type"        collection
     "parcel"       (:parcel rec)
     "h3"           (:h3 rec)
     "valueUsd"     (usd (:value-usd-micros rec))
     "ciLoUsd"      (usd (:ci-lo-usd-micros rec))
     "ciHiUsd"      (usd (:ci-hi-usd-micros rec))
     "nSources"     (:n-sources rec)
     "maxAgeDays"   (:max-age-days rec)
     "license"      (name (or (:license rec) :open))
     "provenance"   (mapv (fn [[s k l]] {"source" (name s) "kind" (name k)
                                         "license" (name l)})
                          (:provenance rec))
     "asof"         at
     "createdAt"    at}))

(defn- rkey
  "Deterministic record key = sanitized parcel id, so re-valuing a parcel
  UPDATES its record (putRecord) instead of piling up history."
  [rec]
  (-> (:parcel rec) (str/replace #"[^A-Za-z0-9._-]" "-") (str/lower-case)))

(defn ->region-record
  "Map a region aggregate (kenchi.fusion/region-aggregate + region/h3/license)
  to the on-wire `com.junkawasaki.kenchi.regionReport` record. AGGREGATE-ONLY:
  names no parcel, so it is publishable even where per-parcel valuation is
  barred. `at` is an injected ISO-8601 timestamp."
  [{:keys [region h3 median-usd-micros p25-usd-micros p75-usd-micros n-comps license]} at]
  (let [usd (fn [m] (when m (format "%.2f" (/ (double m) 1e6))))]
    {"$type"        region-collection
     "region"       region
     "h3"           h3
     "medianUsd"    (usd median-usd-micros)
     "p25Usd"       (usd p25-usd-micros)
     "p75Usd"       (usd p75-usd-micros)
     "nComps"       n-comps
     "license"      (name (or license :open))
     "asof"         at
     "createdAt"    at}))

;; ─────────────────────────────── publish ────────────────────────────────

(defn publishable?
  "Wire-level license gate: a :withhold record (e.g. all inputs restricted)
  must never be PUT. Returns false → caller keeps it Murakumo-only."
  [rec]
  (and (:value-usd-micros rec)
       (not= :withhold (:license rec))))

(defn put-record!
  "PUT one cleared valuation to the PDS via com.atproto.repo.putRecord.
  Returns {:status :uri :cid} on success. Honors the license gate. With
  :dry-run? true (or no :token) it builds the request and returns the AT-URI
  it WOULD write, with no network — the default for offline/dev."
  [{:keys [http-fn json-write json-read] :as _caps} conn rec at
   & [{:keys [dry-run?]}]]
  (when-not (publishable? rec)
    (throw (ex-info "refusing to publish a withheld/empty valuation"
                    {:license (:license rec) :parcel (:parcel rec)})))
  (let [rk   (rkey rec)
        uri  (str "at://" (:pds/did conn) "/" collection "/" rk)
        body {"repo" (:pds/did conn) "collection" collection
              "rkey" rk "record" (->record rec at)}]
    (if (or dry-run? (nil? (:pds/token conn)))
      {:status :dry-run :uri uri :record (get body "record")}
      (let [resp (http-fn {:url (xrpc-url conn "com.atproto.repo.putRecord")
                           :method :post :headers (headers conn)
                           :body (json-write body)})]
        (when-not (#{200 201} (:status resp))
          (throw (ex-info (str "PDS putRecord error " (:status resp))
                          {:status (:status resp) :body (:body resp)})))
        (let [out (json-read (:body resp))]
          {:status :ok :uri (or (:uri out) uri) :cid (:cid out)})))))

(defn put-region-record!
  "PUT one region aggregate to the PDS as a regionReport (rkey = region|h3).
  Dry-run by default (no :token) → returns the AT-URI it would write."
  [{:keys [http-fn json-write json-read] :as _caps} conn region-agg at
   & [{:keys [dry-run?]}]]
  (let [rk   (-> (str (:region region-agg) "-" (:h3 region-agg))
                 (str/replace #"[^A-Za-z0-9._-]" "-") str/lower-case)
        uri  (str "at://" (:pds/did conn) "/" region-collection "/" rk)
        body {"repo" (:pds/did conn) "collection" region-collection
              "rkey" rk "record" (->region-record region-agg at)}]
    (if (or dry-run? (nil? (:pds/token conn)))
      {:status :dry-run :uri uri :record (get body "record")}
      (let [resp (http-fn {:url (xrpc-url conn "com.atproto.repo.putRecord")
                           :method :post :headers (headers conn)
                           :body (json-write body)})]
        (when-not (#{200 201} (:status resp))
          (throw (ex-info (str "PDS putRecord error " (:status resp))
                          {:status (:status resp) :body (:body resp)})))
        {:status :ok :uri uri}))))
