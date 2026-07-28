(ns kotoba.tamaki.replication
  "Encrypted, evidence-bearing disaster-recovery snapshots.

  Kotobase remains the queryable projection. This namespace copies an
  immutable, age-encrypted snapshot of the local authority log to independent
  Murakumo nodes and counts only byte-verified copies as durable replicas."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedInputStream BufferedOutputStream FileInputStream
            FileOutputStream RandomAccessFile]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]
           [java.util.zip GZIPOutputStream]))

(defn read-config [path]
  (edn/read-string (slurp (io/file path))))

(defn- safe-token? [value]
  (and (string? value)
       (boolean (re-matches #"[A-Za-z0-9._@:-]+" value))))

(defn validate-config [config]
  (let [targets (:replication/targets config)
        missing (filterv #(str/blank? (str (get config %)))
                         [:replication/organism :replication/recipient])]
    (when (seq missing)
      (throw (ex-info "Replication config is incomplete" {:missing missing})))
    (when (< (count targets) 2)
      (throw (ex-info "At least two remote targets are required"
                      {:target-count (count targets)})))
    (doseq [{:keys [target/id target/transport target/host target/path]} targets]
      (when-not (and id (= :ssh transport)
                     (safe-token? host)
                     (string? path)
                     (re-matches #"(?:/|~/)[A-Za-z0-9._/-]+" path))
        (throw (ex-info "Unsafe or unsupported replication target"
                        {:target/id id :target/transport transport}))))
    config))

(defn receipt-dir [root]
  (io/file root "replication" "receipts"))

(defn latest-receipt [root]
  (let [dir (receipt-dir root)]
    (when (.isDirectory dir)
      (some->> (.listFiles dir)
               (filter #(and (.isFile %)
                             (str/ends-with? (.getName %) ".edn")))
               (sort-by #(.lastModified %))
               last
               slurp
               edn/read-string))))

(defn due?
  "True when a new sealed replica should be cut.

  Absence of a prior receipt always means due: the min-interval only
  throttles *after* the first successful snapshot. Treating a missing
  receipt as epoch-0 previously let a synthetic or early clock postpone
  the initial disaster-recovery baseline, and `reconcile!` would then
  NPE while computing `:replication/next-at` from a nil receipt."
  [config latest now-ms]
  (if (nil? latest)
    true
    (let [minimum (long (or (:replication/min-interval-ms config) 21600000))
          previous (long (or (:replication/at latest) 0))]
      (>= (- now-ms previous) minimum))))

(defn- committed-length [file]
  (with-open [raf (RandomAccessFile. file "r")]
    (loop [position (.length raf)]
      (cond
        (zero? position) 0
        :else
        (do
          (.seek raf (dec position))
          (if (= 10 (.read raf))
            position
            (recur (dec position))))))))

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn sha256-file [file]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (with-open [in (BufferedInputStream. (FileInputStream. file))]
      (loop []
        (let [n (.read in buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur)))))
    (hex (.digest digest))))

(defn- gzip-committed-log!
  [source destination]
  (let [limit (committed-length source)
        buffer (byte-array 65536)]
    (with-open [in (BufferedInputStream. (FileInputStream. source))
                out (GZIPOutputStream.
                     (BufferedOutputStream. (FileOutputStream. destination)))]
      (loop [remaining limit]
        (when (pos? remaining)
          (let [wanted (int (min (long (alength buffer)) remaining))
                n (.read in buffer 0 wanted)]
            (when (neg? n)
              (throw (ex-info "Authority log changed while snapshotting"
                              {:expected-bytes limit
                               :remaining remaining})))
            (.write out buffer 0 n)
            (recur (- remaining n))))))
    limit))

(def ^:dynamic *run-command*
  (fn [argv]
    (let [process (.start (ProcessBuilder. ^java.util.List (mapv str argv)))
          stdout (future (slurp (.getInputStream process)))
          stderr (future (slurp (.getErrorStream process)))
          exit (.waitFor process)]
      {:exit exit :out @stdout :err @stderr})))

(defn- run-command! [argv]
  (let [result (*run-command* argv)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Replication command failed"
                      {:program (first argv)
                       :exit (:exit result)
                       :error (str/trim (:err result))})))
    result))

(defn seal!
  [root config now-ms]
  (let [source (io/file root "events.edn")]
    (when-not (.isFile source)
      (throw (ex-info "Tamaki authority log not found"
                      {:path (.getPath source)})))
    (let [stage (io/file root "replication" "staging")
          id (str now-ms)
          gzip (io/file stage (str id ".events.edn.gz"))
          sealed (io/file stage (str id ".events.edn.gz.age"))]
      (.mkdirs stage)
      (let [source-bytes (gzip-committed-log! source gzip)]
        (try
          (run-command! ["age" "-r" (:replication/recipient config)
                         "-o" (.getPath sealed) (.getPath gzip)])
          {:snapshot/id id
           :snapshot/path (.getPath sealed)
           :snapshot/source-bytes source-bytes
           :snapshot/sealed-bytes (.length sealed)
           :snapshot/sha256 (sha256-file sealed)}
          (finally
            (Files/deleteIfExists (.toPath gzip))))))))

(defn- remote-path [target organism snapshot-id]
  (str (str/replace (:target/path target) #"/+$" "")
       "/" (name organism) "/" snapshot-id ".events.edn.gz.age"))

(defn replicate-target!
  [snapshot organism target]
  (let [host (:target/host target)
        destination (remote-path target organism (:snapshot/id snapshot))
        parent (subs destination 0 (.lastIndexOf destination "/"))]
    (try
      (run-command! ["ssh" "-o" "BatchMode=yes" "-o" "ConnectTimeout=8"
                     host "mkdir" "-p" parent])
      (run-command! ["scp" "-q" "-o" "BatchMode=yes"
                     "-o" "ConnectTimeout=8"
                     (:snapshot/path snapshot) (str host ":" destination)])
      (let [remote (-> (run-command! ["ssh" "-o" "BatchMode=yes"
                                      "-o" "ConnectTimeout=8"
                                      host "shasum" "-a" "256" destination])
                       :out str/trim (str/split #"\s+") first)
            verified? (= (:snapshot/sha256 snapshot) remote)]
        {:target/id (:target/id target)
         :target/failure-domain (:target/failure-domain target)
         :target/verified? verified?
         :target/sealed-bytes (:snapshot/sealed-bytes snapshot)})
      (catch Exception error
        {:target/id (:target/id target)
         :target/failure-domain (:target/failure-domain target)
         :target/verified? false
         :target/error (ex-message error)}))))

(defn- write-edn-atomically! [file value]
  (.mkdirs (.getParentFile file))
  (let [temporary (io/file (.getParentFile file)
                           (str "." (.getName file) ".tmp"))]
    (spit temporary (str (pr-str value) "\n"))
    (Files/move (.toPath temporary) (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE]))))

(defn update-observation!
  [path durable-replicas]
  (let [file (io/file path)
        source (slurp file)
        marker #":durable-replicas\s+\d+"]
    (when-not (re-find marker source)
      (throw (ex-info "Observation has no durable replica stock"
                      {:path path})))
    ;; Preserve operator comments and stable reviewable formatting. The
    ;; observation is a private SSoT, not a generated one-line projection.
    (let [updated (str/replace source marker
                               (str ":durable-replicas "
                                    durable-replicas))
          temporary (io/file (.getParentFile file)
                             (str "." (.getName file) ".tmp"))]
      (spit temporary updated)
      (Files/move (.toPath temporary) (.toPath file)
                  (into-array StandardCopyOption
                              [StandardCopyOption/REPLACE_EXISTING
                               StandardCopyOption/ATOMIC_MOVE])))))

(defn reconcile!
  [root config now-ms]
  (let [config (validate-config config)
        latest (latest-receipt root)]
    (if-not (due? config latest now-ms)
      ;; `due?` is true whenever `latest` is nil, so this branch always has a
      ;; prior receipt to restate. Fail closed rather than invent a schedule.
      (do
        (when (nil? latest)
          (throw (ex-info "Replication not-due without a prior receipt" {})))
        (assoc latest
               :replication/status :not-due
               :replication/next-at
               (+ (long (:replication/at latest))
                  (long (or (:replication/min-interval-ms config) 21600000)))))
      (let [snapshot (seal! root config now-ms)
            targets (mapv #(replicate-target!
                            snapshot (:replication/organism config) %)
                          (:replication/targets config))
            verified (filter :target/verified? targets)
            domains (set (keep :target/failure-domain verified))
            receipt {:replication/version 1
                     :replication/status
                     (if (and (>= (count verified) 2)
                              (>= (count domains) 2))
                       :healthy :degraded)
                     :replication/at now-ms
                     :replication/snapshot
                     (dissoc snapshot :snapshot/path)
                     ;; The local authority plus every verified remote copy.
                     :replication/durable-replicas (inc (count verified))
                     :replication/failure-domains domains
                     :replication/targets targets}
            file (io/file (receipt-dir root)
                          (str (:snapshot/id snapshot) ".edn"))]
        (write-edn-atomically! file receipt)
        (Files/deleteIfExists (.toPath (io/file (:snapshot/path snapshot))))
        receipt))))
