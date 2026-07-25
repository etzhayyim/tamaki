(ns kotoba.tamaki.store-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [kotoba.tamaki.store :as store]))

(def config {:url "https://example.test"
             :graph "graph-1"
             :token "secret"})

(deftest kotobase-append-wire-contract
  (let [request (atom nil)
        event {:tamaki.event/id "e1"
               :tamaki.event/run "r1"
               :tamaki.event/at 1}]
    (binding [store/*http-fn*
              (fn [req]
                (reset! request req)
                {:status 200 :body "{\"ok\":true}"})]
      (is (= event (store/append-kotobase-event! config event))))
    (is (re-find #"datomic\.transact$" (:url @request)))
    (is (= "Bearer secret" (get-in @request [:headers "authorization"])))
    (let [body (json/parse-string (:body @request) true)]
      (is (= "graph-1" (:graph body)))
      (is (re-find #":tamaki\.event/blob" (:tx_edn body))))))

(deftest kotobase-read-decodes-and-orders-events
  (let [late {:tamaki.event/id "e2" :tamaki.event/run "r1"
              :tamaki.event/at 2}
        early {:tamaki.event/id "e1" :tamaki.event/run "r1"
               :tamaki.event/at 1}
        body (json/generate-string
              {:rows_edn [[(pr-str (pr-str late))]
                          [(pr-str (pr-str early))]]})]
    (binding [store/*http-fn* (fn [_] {:status 200 :body body})]
      (is (= [early late] (store/read-kotobase-events config))))))

(deftest remote-errors-fail-closed
  (binding [store/*http-fn*
            (fn [_] {:status 503 :body "unavailable"})]
    (is (thrown-with-msg? Exception #"Kotobase XRPC"
                          (store/append-kotobase-event!
                           config {:tamaki.event/id "e1"})))))
