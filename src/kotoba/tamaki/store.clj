(ns kotoba.tamaki.store
  "Append-only AgentRun events on local disk or the shared Kotobase Datom plane."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def event-attr :tamaki.event/blob)
(def supported-backends #{:file :kotobase :dual})
(def ^:private http-client (delay (HttpClient/newHttpClient)))
(def ^:private local-append-lock (Object.))

(def ^:dynamic *http-fn*
  (fn [{:keys [url headers body]}]
    (let [builder (doto (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis 120000))
                    (.POST (HttpRequest$BodyPublishers/ofString body)))
          _ (doseq [[k v] headers] (.header builder k v))
          response (.send @http-client (.build builder)
                          (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response) :body (.body response)})))

(defn default-root []
  (or (System/getenv "TAMAKI_STATE_DIR") ".tamaki"))

(defn backend []
  (keyword (or (System/getenv "TAMAKI_STORE") "file")))

(defn kotobase-config []
  {:url (some-> (or (System/getenv "KOTOBA_URL")
                    (System/getenv "TAMAKI_KOTOBA_URL"))
                (str/replace #"/+$" ""))
   :graph (or (System/getenv "KOTOBA_GRAPH")
              (System/getenv "TAMAKI_KOTOBA_GRAPH"))
   :token (or (System/getenv "KOTOBA_TOKEN")
              (System/getenv "TAMAKI_KOTOBA_TOKEN"))})

(defn kotobase-ready? [{:keys [url graph token]}]
  (every? #(not (str/blank? %)) [url graph token]))

(defn event-file [root]
  (io/file root "events.edn"))

(defn ensure-root! [root]
  (.mkdirs (io/file root))
  root)

(defn read-local-events
  [root]
  (let [f (event-file root)]
    (if-not (.exists f)
      []
      (let [content (slurp f)
            complete-tail? (str/ends-with? content "\n")
            lines (->> (str/split-lines content)
                       (remove str/blank?)
                       vec)]
        (reduce-kv
         (fn [events index line]
           (try
             (conj events (edn/read-string line))
             (catch Exception error
               ;; An interrupted append may leave only the unterminated final
               ;; EDN line incomplete. Keep committed events, but never hide
               ;; corruption in a line whose terminating newline was written.
               (if (and (= index (dec (count lines)))
                        (not complete-tail?))
                 events
                 (throw error)))))
         []
         lines)))))

(defn append-local-event!
  [root event]
  (locking local-append-lock
    (ensure-root! root)
    (spit (event-file root) (str (pr-str event) "\n") :append true))
  event)

(defn- xrpc!
  [{:keys [url token]} method body]
  (let [response (*http-fn*
                  {:url (str url "/xrpc/ai.gftd.apps.kotobase.datomic." method)
                   :headers {"content-type" "application/json"
                             "accept" "application/json"
                             "authorization" (str "Bearer " token)}
                   :body (json/generate-string body)})]
    (when-not (#{200 201} (:status response))
      (throw (ex-info (str "Kotobase XRPC " method " failed")
                      {:status (:status response) :body (:body response)})))
    (json/parse-string (or (:body response) "{}") true)))

(defn append-kotobase-event!
  [config event]
  (when-not (kotobase-ready? config)
    (throw (ex-info "Kotobase store requires URL, graph and token"
                    {:required [:KOTOBA_URL :KOTOBA_GRAPH :KOTOBA_TOKEN]})))
  (xrpc! config "transact"
         {:graph (:graph config)
          :tx_edn (pr-str [{:db/id (:tamaki.event/id event)
                            event-attr (pr-str event)}])})
  event)

(defn- decode-row-cell [cell]
  (let [value (if (string? cell)
                (try (edn/read-string cell) (catch Exception _ cell))
                cell)]
    (if (string? value) (edn/read-string value) value)))

(defn read-kotobase-events
  [config]
  (when-not (kotobase-ready? config)
    (throw (ex-info "Kotobase store requires URL, graph and token"
                    {:required [:KOTOBA_URL :KOTOBA_GRAPH :KOTOBA_TOKEN]})))
  (let [response (xrpc!
                  config "q"
                  {:graph (:graph config)
                   :query_edn
                   (pr-str {:find ['?blob]
                            :where [['?event event-attr '?blob]]})
                   :inputs_edn []})]
    (->> (or (:rows_edn response) (:rows response) [])
         (map (comp decode-row-cell first))
         (sort-by (juxt :tamaki.event/at :tamaki.event/id))
         vec)))

(defn- unsupported-backend! [kind]
  (throw (ex-info (str "Unsupported TAMAKI_STORE backend: " (name kind))
                  {:backend kind :supported supported-backends})))

(defn read-events [root]
  (case (backend)
    :file (read-local-events root)
    :kotobase (read-kotobase-events (kotobase-config))
    :dual (read-kotobase-events (kotobase-config))
    (unsupported-backend! (backend))))

(defn append-event! [root event]
  (case (backend)
    :file (append-local-event! root event)
    :kotobase (append-kotobase-event! (kotobase-config) event)
    :dual (do
            ;; Shared state is the commit point. Never leave a local-only fact.
            (append-kotobase-event! (kotobase-config) event)
            (append-local-event! root event))
    (unsupported-backend! (backend))))

(defn readiness []
  (let [kind (backend)
        config (kotobase-config)]
    {:backend kind
     :ok? (and (contains? supported-backends kind)
               (if (contains? #{:kotobase :dual} kind)
                 (kotobase-ready? config)
                 true))
     :error (when-not (contains? supported-backends kind)
              (str "Unsupported TAMAKI_STORE backend: " (name kind)))
     :kotobase (when (contains? #{:kotobase :dual} kind)
                 {:url (:url config) :graph (:graph config)
                  :token? (not (str/blank? (:token config)))})
     :local-root (when (contains? #{:file :dual} kind) (default-root))}))
