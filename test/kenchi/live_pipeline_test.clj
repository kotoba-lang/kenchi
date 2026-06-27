(ns kenchi.live-pipeline-test
  "Contracts for the live-wiring modules: ingest normalization, PDS publish
  gate, and flywheel recalibration — all offline via injected I/O."
  (:require [clojure.test :refer [deftest is testing]]
            [kenchi.ingest :as ingest]
            [kenchi.publish :as publish]
            [kenchi.flywheel :as flywheel]
            [kenchi.fusion :as fusion]))

;; ─────────────────────────────── 1. ingest ───────────────────────────────

(def jp-fixture
  {:status "OK"
   :data [{:Type "宅地" :Period "2024年第1四半期" :TradePrice "99000000"}
          {:Type "宅地" :Period "2023年第3四半期" :TradePrice "101000000"}]})

(deftest ingest-normalizes-to-observations
  (let [caps (ingest/fixture-caps {:jp-registry jp-fixture})
        obs  (ingest/fetch-source caps :jp-registry {:year 2024 :area "13" :now-year 2026})]
    (is (= 2 (count obs)))
    (is (every? #(= :jp-registry (:source %)) obs))
    (is (every? #(= :sale (:kind %)) obs))
    (is (every? #(= :jpy (:currency %)) obs))
    (is (every? #(= :open (:license %)) obs))           ; stamped from adapter
    (is (= #{99000000 101000000} (set (map :value obs))))
    (is (every? #(>= (:age-days %) 0) obs))))

(deftest ingest-survives-a-dead-source
  (testing "a non-200 / missing body yields [], never an exception"
    (let [caps {:http-fn (fn [_] {:status 503 :body nil}) :json-read identity}]
      (is (= [] (ingest/fetch-source caps :jp-registry {:year 2024 :area "13"}))))))

;; ─────────────────────────────── 2. publish ──────────────────────────────

(def cleared
  {:parcel "jp-13113-shibuya-1-2-3" :h3 "8f2f5a0e"
   :value-usd-micros 627000000000 :ci-lo-usd-micros 610000000000
   :ci-hi-usd-micros 660000000000 :n-sources 5 :max-age-days 200
   :license :derived-only
   :provenance [[:jp-registry :sale :open] [:portal-suumo :avm :derived-only]]})

(deftest publish-builds-lexicon-record-and-aturi
  (let [conn (publish/pds-conn "https://pds.example" "did:web:example")
        caps {:json-write pr-str :json-read identity :http-fn (fn [_] {:status 200 :body {}})}
        out  (publish/put-record! caps conn cleared "2026-06-27T00:00:00Z" {:dry-run? true})]
    (is (= :dry-run (:status out)))
    (is (= "at://did:web:example/com.junkawasaki.kenchi.valuation/jp-13113-shibuya-1-2-3"
           (:uri out)))
    (let [r (:record out)]
      (is (= "627000.00" (get r "valueUsd")))           ; micros → USD decimal
      (is (= "derived-only" (get r "license")))
      (is (= 2 (count (get r "provenance")))))))

(deftest publish-refuses-withheld
  (testing "wire-level license gate: a withheld/empty valuation never PUTs"
    (let [conn (publish/pds-conn "https://pds.example" "did:web:example")
          caps {:json-write pr-str :json-read identity :http-fn (fn [_] {:status 200 :body {}})}]
      (is (false? (publish/publishable? (assoc cleared :license :withhold))))
      (is (thrown? Exception
                   (publish/put-record! caps conn (assoc cleared :license :withhold)
                                        "2026-06-27T00:00:00Z"))))))

;; ─────────────────────────────── 3. flywheel ─────────────────────────────

(def cases
  [{:realized-usd-micros (long (* 99000000 0.0064 1e6))
    :observations [{:source :jp-registry :kind :sale :value 99000000 :currency :jpy :age-days 90 :confidence 0.95}
                   {:source :scraper-x :kind :list :value 180000000 :currency :jpy :age-days 5 :confidence 0.2}]}
   {:realized-usd-micros (long (* 102000000 0.0064 1e6))
    :observations [{:source :jp-registry :kind :sale :value 101000000 :currency :jpy :age-days 60 :confidence 0.95}
                   {:source :scraper-x :kind :list :value 200000000 :currency :jpy :age-days 3 :confidence 0.2}]}])

(deftest flywheel-downweights-a-bad-source
  (let [rel' (flywheel/recalibrate fusion/default-reliability cases)]
    (is (< (:scraper-x rel') (:scraper-x fusion/default-reliability)))
    (is (>= (:jp-registry rel') 0.5))))

(deftest flywheel-recalibration-does-not-worsen-backtest
  (let [{:keys [before after]} (flywheel/improvement fusion/default-reliability cases)]
    (is (number? before))
    (is (<= after before))))                            ; sharper, or no worse
