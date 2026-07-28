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
(def supported-backends #{:file :kotobase :dual :federated})
(def ^:private http-client (delay (HttpClient/newHttpClient)))
(def ^:private local-append-lock (Object.))
(def ^:private local-read-cache (atom {}))

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

(defn outbox-dir [root]
  (io/file root "replication" "outbox"))

(defn- outbox-file [root event]
  (io/file (outbox-dir root)
           (str (str/replace (str (:tamaki.event/id event))
                             #"[^A-Za-z0-9._-]" "_")
                ".edn")))

(defn ensure-root! [root]
  (.mkdirs (io/file root))
  root)

(defn read-local-events
  [root]
  (let [f (event-file root)]
    (if-not (.exists f)
      []
      (let [path (.getCanonicalPath f)
            signature [(.length f) (.lastModified f)]
            cached (get @local-read-cache path)]
        (if (= signature (:signature cached))
          (:events cached)
          (let [content (slurp f)
                complete-tail? (str/ends-with? content "\n")
                lines (->> (str/split-lines content)
                           (remove str/blank?)
                           vec)
                parsed
                (reduce-kv
                 (fn [events index line]
                   (try
                     (conj events (edn/read-string line))
                     (catch Exception error
                       ;; An interrupted append may leave only the unterminated
                       ;; final EDN line incomplete. Keep committed events, but
                       ;; never hide corruption in a terminated line.
                       (if (and (= index (dec (count lines)))
                                (not complete-tail?))
                         events
                         (throw error)))))
                 []
                 lines)
                final-signature [(.length f) (.lastModified f)]]
            ;; Cache only a stable read. A concurrent append causes the next
            ;; call to retry from the log rather than publishing a stale view.
            (when (= signature final-signature)
              (swap! local-read-cache assoc path
                     {:signature final-signature :events parsed}))
            parsed))))))

(defn append-local-event!
  [root event]
  (locking local-append-lock
    (ensure-root! root)
    (let [f (event-file root)
          path (.getCanonicalPath f)
          before [(.length f) (.lastModified f)]
          cached (get @local-read-cache path)]
      (spit f (str (pr-str event) "\n") :append true)
      (if (= before (:signature cached))
        (swap! local-read-cache assoc path
               {:signature [(.length f) (.lastModified f)]
                :events (conj (:events cached) event)})
        (swap! local-read-cache dissoc path))))
  event)

(defn append-federated-event!
  "Commit locally first, then enqueue the same immutable event for Kotobase
  replication. Network loss cannot stop the organism or erase its local
  memory; replication debt remains explicit in the outbox."
  [root event]
  (append-local-event! root event)
  (.mkdirs (outbox-dir root))
  (spit (outbox-file root event) (pr-str event))
  event)

(defn pending-replication [root]
  (let [dir (outbox-dir root)]
    (if-not (.isDirectory dir)
      []
      (->> (.listFiles dir)
           (filter #(and (.isFile %)
                         (str/ends-with? (.getName %) ".edn")))
           (sort-by #(.getName %))
           vec))))

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
    :federated (read-local-events root)
    (unsupported-backend! (backend))))

(defn append-event! [root event]
  (case (backend)
    :file (append-local-event! root event)
    :kotobase (append-kotobase-event! (kotobase-config) event)
    :dual (do
            ;; Shared state is the commit point. Never leave a local-only fact.
            (append-kotobase-event! (kotobase-config) event)
            (append-local-event! root event))
    :federated (append-federated-event! root event)
    (unsupported-backend! (backend))))

(defn sync-federated!
  "Flush locally committed events to Kotobase. Each event has its own outbox
  file so an interrupted sync preserves the exact remaining debt. Murakumo
  may independently replicate the sealed local state directory; this function
  handles the queryable Kotobase projection."
  [root]
  (let [config (kotobase-config)
        pending (pending-replication root)]
    (if-not (kotobase-ready? config)
      {:replication/status :deferred
       :replication/pending (count pending)
       :replication/succeeded 0
       :replication/failed (count pending)
       :replication/reason :kotobase-unconfigured}
      (let [results
            (mapv
             (fn [file]
               (try
                 (append-kotobase-event!
                  config (edn/read-string (slurp file)))
                 (if (.delete file)
                   {:ok? true :file (.getName file)}
                   {:ok? false :file (.getName file)
                    :error :outbox-delete-failed})
                 (catch Exception e
                   {:ok? false :file (.getName file)
                    :error (.getMessage e)})))
             pending)
            succeeded (count (filter :ok? results))]
        {:replication/status
         (if (= succeeded (count results)) :synced :degraded)
         :replication/pending (count pending)
         :replication/succeeded succeeded
         :replication/failed (- (count results) succeeded)
         :replication/results results}))))

(defn readiness []
  (let [kind (backend)
        config (kotobase-config)]
    (cond->
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
      :local-root (when (contains? #{:file :dual :federated} kind)
                    (default-root))}
      (= :federated kind)
      (assoc :replication
             {:mode :local-first
              :pending (count (pending-replication (default-root)))
              :kotobase-ready? (kotobase-ready? config)
              :murakumo-transport :sealed-state-directory}))))
