(ns kenchi.http
  "Real JVM HTTP client (java.net.http) + real JSON reader (org.clojure/data.json)
  packaged as the injected host-caps. This is the PRODUCTION SWAP for the offline
  `kenchi.ingest/fixture-caps`: identical {:url :method :headers :query :body}
  contract (the one langchain.kotoba-db defines), backed by the network.

    (require '[kenchi.http :as http] '[kenchi.ingest :as ingest])
    (ingest/fetch-source (http/caps) :uk-landreg {:sparql q})

  Zero workspace deps beyond data.json; java.net.http ships with the JDK
  (same choice langchain-clj made)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(def ^:private client
  (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) (.build)))

(defn- encode-query [q]
  (when (seq q)
    (->> q
         (map (fn [[k v]] (str (URLEncoder/encode (str k) "UTF-8") "="
                               (URLEncoder/encode (str v) "UTF-8"))))
         (str/join "&"))))

(defn http-fn
  "Inject as :http-fn. GET/POST with header + query map → {:status :body}."
  [{:keys [url method headers query body]}]
  (let [qs   (encode-query query)
        full (if qs (str url "?" qs) url)
        b    (HttpRequest/newBuilder (URI/create full))]
    (doseq [[k v] headers] (.header b k v))
    (let [req  (-> (case (or method :get)
                     :get  (.GET b)
                     :post (.POST b (HttpRequest$BodyPublishers/ofString (or body ""))))
                   (.timeout (Duration/ofSeconds 25))
                   (.build))
          resp (.send client req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)})))

(defn json-read [s] (json/read-str s :key-fn keyword))

(defn caps
  "Host-caps for live ingest: a real http client + a real JSON reader."
  []
  {:http-fn http-fn :json-read json-read})
