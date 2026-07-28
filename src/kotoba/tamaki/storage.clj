(ns kotoba.tamaki.storage
  "Fail-closed disk homeostasis.

  Diskspace reports are observations, never deletion authority.  Mutation is
  limited to exact candidates declared in a private policy.  Recreatable
  directories must be below an allowed root and have no open files.  Annex
  content is dropped only after every required remote passes fsck and
  git-annex confirms the configured copy count."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file FileVisitOption Files Path]
           [java.util.concurrent TimeUnit]))

(def candidate-types
  #{:recreatable-directory :recreatable-children :annex-dataset})

(def command-timeout-ms 120000)

(defn bounded-command
  [argv cwd]
  (let [builder (ProcessBuilder. ^java.util.List argv)
        _ (when cwd (.directory builder (io/file cwd)))
        process (.start builder)
        out (future (slurp (.getInputStream process)))
        err (future (slurp (.getErrorStream process)))
        completed? (.waitFor process command-timeout-ms TimeUnit/MILLISECONDS)]
    (if completed?
      {:exit (.exitValue process) :out @out :err @err}
      (do
        (.destroyForcibly process)
        (.waitFor process)
        {:exit 124
         :out (deref out 1000 "")
         :err (str (deref err 1000 "") "\nstorage command timed out")}))))

(def ^:dynamic *command-fn* bounded-command)

(defn canonical-path [path]
  (.getCanonicalPath (io/file path)))

(defn- descendant?
  [root path]
  (let [root (canonical-path root)
        path (canonical-path path)
        prefix (str root java.io.File/separator)]
    (and (not= root path) (str/starts-with? path prefix))))

(defn validate-policy
  [policy]
  (let [volume (:storage.policy/volume policy)
        high (:storage.policy/high-watermark policy)
        target (:storage.policy/target-watermark policy)
        roots (mapv canonical-path (:storage.policy/allowed-roots policy))
        candidates (:storage.policy/candidates policy)]
    (when-not (= 1 (:storage.policy/version policy))
      (throw (ex-info "Storage policy requires version 1" {})))
    (when (or (str/blank? volume) (not (.isDirectory (io/file volume))))
      (throw (ex-info "Storage policy volume is unavailable" {:volume volume})))
    (when-not (and (number? high) (number? target)
                   (< 0.0 (double target) (double high) 1.0))
      (throw (ex-info
              "Storage watermarks must satisfy 0 < target < high < 1"
              {:target target :high high})))
    (when (empty? roots)
      (throw (ex-info "Storage policy requires allowed roots" {})))
    (doseq [candidate candidates
            :let [type (:storage.candidate/type candidate)
                  path (:storage.candidate/path candidate)]]
      (when-not (contains? candidate-types type)
        (throw (ex-info "Unknown storage candidate type"
                        {:candidate (:storage.candidate/id candidate)
                         :type type})))
      (when (or (str/blank? (str (:storage.candidate/id candidate)))
                (str/blank? path))
        (throw (ex-info "Storage candidate requires id and path"
                        {:candidate candidate})))
      (when-not (some #(descendant? % path) roots)
        (throw (ex-info "Storage candidate is outside allowed roots"
                        {:candidate (:storage.candidate/id candidate)})))
      (when (and (contains? #{:recreatable-directory
                              :recreatable-children} type)
                 (not= :recreated
                       (:storage.candidate/recoverability candidate)))
        (throw (ex-info "Deletion requires explicit recreated recoverability"
                        {:candidate (:storage.candidate/id candidate)})))
      (when (= :annex-dataset type)
        (let [copies (or (:storage.candidate/min-copies candidate)
                         (:storage.policy/min-annex-copies policy))
              remotes (:storage.candidate/required-remotes candidate)]
          (when-not (and (integer? copies) (>= copies 2) (seq remotes))
            (throw (ex-info
                    "Annex drop requires at least two copies and named remotes"
                    {:candidate (:storage.candidate/id candidate)}))))))
    (assoc policy :storage.policy/allowed-roots roots)))

(defn read-policy [path]
  (let [file (io/file path)]
    (when-not (.isFile file)
      (throw (ex-info "Storage policy file not found" {:path path})))
    (validate-policy (edn/read-string (slurp file)))))

(defn observe-volume
  [policy]
  (let [file (io/file (:storage.policy/volume policy))
        total (.getTotalSpace file)
        free (.getUsableSpace file)
        used (- total free)]
    {:storage/total-bytes total
     :storage/used-bytes used
     :storage/free-bytes free
     :storage/usage-ratio (if (pos? total)
                            (/ (double used) (double total))
                            1.0)}))

(defn read-diskspace-report
  [policy]
  (when-let [path (:storage.policy/diskspace-report policy)]
    (let [file (io/file path)]
      (when (.isFile file)
        (let [report (edn/read-string (slurp file))
              candidates (:diskspace.report/cleanup-candidates report)]
          {:storage.discovery/schema (:diskspace.report/schema report)
           :storage.discovery/candidates (count candidates)
           :storage.discovery/bytes
           (reduce + 0 (keep :cleanup.candidate/bytes candidates))})))))

(defn- allocated-bytes [path]
  (if-not (.exists (io/file path))
    0
    (let [result (*command-fn* ["/usr/bin/du" "-sk" path] nil)
          amount (some-> (:out result) str/trim (str/split #"\s+") first)]
      (if (and (zero? (:exit result)) (re-matches #"\d+" (or amount "")))
        (* 1024 (Long/parseLong amount))
        0))))

(defn- open-files?
  [path]
  (when (.exists (io/file path))
    (let [result (*command-fn* ["/usr/sbin/lsof" "+D" path] nil)]
      (and (zero? (:exit result)) (not (str/blank? (:out result)))))))

(defn- expand-candidate
  [candidate]
  (if (= :recreatable-children (:storage.candidate/type candidate))
    (let [parent (io/file (:storage.candidate/path candidate))]
      (if (.isDirectory parent)
        (->> (.listFiles parent)
             (filter #(.isDirectory %))
             (mapv
              (fn [child]
                (-> candidate
                    (assoc :storage.candidate/type :recreatable-directory
                           :storage.candidate/path (.getPath child)
                           :storage.candidate/parent
                           (:storage.candidate/id candidate)
                           :storage.candidate/id
                           (keyword
                            (str (name (:storage.candidate/id candidate))
                                 "/" (.getName child))))))))
        []))
    [candidate]))

(defn inspect-candidate
  [candidate]
  (let [path (:storage.candidate/path candidate)
        file (io/file path)
        bytes (if (= :annex-dataset (:storage.candidate/type candidate))
                (allocated-bytes (str path "/.git/annex/objects"))
                (allocated-bytes path))
        disposition
        (cond
          (false? (:storage.candidate/enabled? candidate true)) :disabled
          (not (.exists file)) :missing
          (zero? bytes) :empty
          (and (:storage.candidate/requires-no-open-files? candidate true)
               (not= :annex-dataset (:storage.candidate/type candidate))
               (open-files? path)) :blocked-open-files
          :else :eligible)]
    (assoc candidate
           :storage.candidate/bytes bytes
           :storage.candidate/disposition disposition)))

(defn plan
  [policy observation]
  (let [policy (validate-policy policy)
        pressure? (> (:storage/usage-ratio observation)
                     (:storage.policy/high-watermark policy))
        budget (long (or (:storage.policy/max-reclaim-bytes-per-tick policy)
                         Long/MAX_VALUE))
        target-used (* (:storage/total-bytes observation)
                       (:storage.policy/target-watermark policy))
        needed (max 0 (- (:storage/used-bytes observation) target-used))
        inspected
        (->> (:storage.policy/candidates policy)
             (mapcat expand-candidate)
             (map inspect-candidate)
             (sort-by (juxt #(or (:storage.candidate/priority %) 100)
                            #(str (:storage.candidate/id %))))
             vec)
        limit (min (double budget) (double needed))
        [selected _]
        (if-not pressure?
          [#{} 0]
          (reduce
           (fn [[ids total] candidate]
             (let [bytes (:storage.candidate/bytes candidate)]
               (if (and (= :eligible
                           (:storage.candidate/disposition candidate))
                        (< total limit))
                 [(conj ids (:storage.candidate/id candidate))
                  (+ total bytes)]
                 [ids total])))
           [#{} 0]
           inspected))
        candidates
        (mapv #(assoc % :storage.candidate/selected?
                      (contains? selected (:storage.candidate/id %)))
              inspected)]
    {:storage/actor :tamaki/storage-curator
     :storage/status (if pressure? :pressure :healthy)
     :storage/observation observation
     :storage/target-watermark (:storage.policy/target-watermark policy)
     :storage/high-watermark (:storage.policy/high-watermark policy)
     :storage/reclaim-needed-bytes (long needed)
     :storage/planned-reclaim-bytes
     (reduce + 0 (map :storage.candidate/bytes
                      (filter :storage.candidate/selected? candidates)))
     :storage/candidates candidates
     :storage/discovery (read-diskspace-report policy)}))

(defn- delete-tree!
  [path]
  (let [root (.toPath (io/file path))]
    (when (Files/exists root (make-array java.nio.file.LinkOption 0))
      (with-open [paths (Files/walk root (make-array FileVisitOption 0))]
        (doseq [^Path item
                (sort-by #(count (str %)) >
                         (iterator-seq (.iterator paths)))]
          (Files/deleteIfExists item))))))

(defn- apply-annex!
  [policy candidate]
  (let [path (:storage.candidate/path candidate)
        remotes (:storage.candidate/required-remotes candidate)
        copies (or (:storage.candidate/min-copies candidate)
                   (:storage.policy/min-annex-copies policy))
        checks
        (mapv
         (fn [remote]
           (let [result (*command-fn*
                         ["git" "annex" "fsck" "--from" remote "--fast"] path)]
             {:remote remote :ok? (zero? (:exit result))}))
         remotes)]
    (if-not (every? :ok? checks)
      {:storage.outcome/status :blocked
       :storage.outcome/reason :annex-remote-verification-failed
       :storage.outcome/remote-checks checks}
      (let [result (*command-fn*
                    ["git" "-c" (str "annex.numcopies=" copies)
                     "annex" "drop" "--all"] path)]
        {:storage.outcome/status (if (zero? (:exit result))
                                   :reclaimed :blocked)
         :storage.outcome/reason (if (zero? (:exit result))
                                   :annex-copies-verified
                                   :annex-copy-count-not-satisfied)
         :storage.outcome/remote-checks checks}))))

(defn apply-plan!
  [policy storage-plan]
  (mapv
   (fn [candidate]
     (let [before (:storage.candidate/bytes candidate)]
       (if-not (:storage.candidate/selected? candidate)
         {:storage.outcome/id (:storage.candidate/id candidate)
          :storage.outcome/status :not-selected
          :storage.outcome/reclaimed-bytes 0}
         (try
           (let [result
                 (if (= :annex-dataset (:storage.candidate/type candidate))
                   (apply-annex! policy candidate)
                   (do (delete-tree! (:storage.candidate/path candidate))
                       {:storage.outcome/status :reclaimed
                        :storage.outcome/reason :recreatable-content-deleted}))
                 after
                 (if (= :annex-dataset (:storage.candidate/type candidate))
                   (allocated-bytes
                    (str (:storage.candidate/path candidate)
                         "/.git/annex/objects"))
                   (allocated-bytes (:storage.candidate/path candidate)))]
             (merge
              {:storage.outcome/id (:storage.candidate/id candidate)
               :storage.outcome/reclaimed-bytes (max 0 (- before after))}
              result))
           (catch Exception exception
             {:storage.outcome/id (:storage.candidate/id candidate)
              :storage.outcome/status :blocked
              :storage.outcome/reason :execution-failed
              :storage.outcome/reclaimed-bytes 0
              :storage.outcome/error (.getMessage exception)})))))
   (:storage/candidates storage-plan)))

(defn event
  [storage-plan outcomes before after now-ms]
  {:tamaki.event/version 1
   :tamaki.event/id (str (random-uuid))
   :tamaki.event/run "storage::curator"
   :tamaki.event/parent nil
   :tamaki.event/kind :storage/reconciled
   :tamaki.event/at now-ms
   :tamaki.event/data
   {:storage/actor :tamaki/storage-curator
    :storage/status (:storage/status storage-plan)
    :storage/usage-ratio-before (:storage/usage-ratio before)
    :storage/usage-ratio-after (:storage/usage-ratio after)
    :storage/free-bytes-after (:storage/free-bytes after)
    :storage/reclaimed-bytes
    (reduce + 0 (map :storage.outcome/reclaimed-bytes outcomes))
    :storage/outcomes
    (mapv #(select-keys %
                        [:storage.outcome/id :storage.outcome/status
                         :storage.outcome/reason
                         :storage.outcome/reclaimed-bytes])
          outcomes)
    :storage/discovery (:storage/discovery storage-plan)}})
