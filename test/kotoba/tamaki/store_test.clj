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

(deftest unknown-backend-fails-closed-and-is-observable
  (with-redefs [store/backend (constantly :typo)]
    (is (thrown-with-msg? Exception #"Unsupported TAMAKI_STORE backend: typo"
                          (store/read-events "ignored")))
    (is (thrown-with-msg? Exception #"Unsupported TAMAKI_STORE backend: typo"
                          (store/append-event! "ignored" {})))
    (is (= {:backend :typo
            :ok? false
            :error "Unsupported TAMAKI_STORE backend: typo"
            :kotobase nil
            :local-root nil}
           (store/readiness)))))

(deftest local-read-recovers-from-an-incomplete-tail
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "tamaki-store-test"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        event {:tamaki.event/id "e1" :tamaki.event/at 1}]
    (spit (store/event-file root) (str (pr-str event) "\n{:incomplete"))
    (is (= [event] (store/read-local-events root)))))

(deftest local-read-rejects-corruption-before-the-tail
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "tamaki-store-test"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        event {:tamaki.event/id "e1" :tamaki.event/at 1}]
    (spit (store/event-file root)
          (str "{:corrupt\n" (pr-str event) "\n"))
    (is (thrown? Exception (store/read-local-events root)))))

(deftest local-read-rejects-newline-terminated-corrupt-tail
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "tamaki-store-test"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        event {:tamaki.event/id "e1" :tamaki.event/at 1}]
    (spit (store/event-file root)
          (str (pr-str event) "\n{:corrupt\n"))
    (is (thrown? Exception (store/read-local-events root)))))
