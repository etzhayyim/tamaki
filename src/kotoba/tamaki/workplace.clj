(ns kotoba.tamaki.workplace
  "Organization-workplace boundary for a repository-bound artificial organism.

  A workplace may assign responsibility, project redacted activity, and submit
  typed intents. It does not become the organism's process, memory, lifecycle,
  source, issue, or effect authority."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.channels FileChannel]
           [java.nio.file Files StandardCopyOption StandardOpenOption]
           [java.util UUID]))

(def schema "kotoba.ao.worker-assignment.v1")

(def retained-authority
  {:memory :organism-local
   :lifecycle :organism-local
   :source :repository-local})

(def receipt-schema "kotoba.ao.worker-intent-receipt.v1")
(def terminal-effects
  ;; :executing is intentionally at-most-once. A process crash after this
  ;; commit requires explicit operator recovery; an automatic replay could
  ;; duplicate an external effect.
  #{:executing :succeeded :refused :failed :expired})
(def default-limit 50)

(defn assignment
  "Build the safe public workplace contract for a Tamaki-family AO."
  [{:keys [worker-id organization subject repository incarnation capabilities
           issue-authority]}]
  (when-not (every? some? [worker-id organization subject repository])
    (throw (ex-info "worker identity, organization, subject, and repository are required"
                    {:type :workplace/invalid-assignment})))
  {:ao.worker/schema schema
   :ao.worker/id worker-id
   :ao.worker/kind :artificial-organism
   :ao.worker/organization organization
   :ao.worker/subject subject
   :ao.worker/repository repository
   :ao.worker/runtime :external-supervisor
   :ao.worker/status :active
   :ao.worker/capabilities (set capabilities)
   :ao.worker/authority (assoc retained-authority
                               :issue (or issue-authority :radicle-first))
   :ao.worker/incarnation incarnation})

(defn intent-decision
  "Admit an organization intent to Tamaki's inbox. This decision deliberately
  does not execute an effect; normal organism gates remain downstream."
  [worker-assignment intent now-ms]
  (let [reason
        (cond
          (not= (:intent/organization intent)
                (:ao.worker/organization worker-assignment))
          :organization-boundary

          (not= (:intent/worker intent) (:ao.worker/id worker-assignment))
          :worker-boundary

          (not (contains? (:ao.worker/capabilities worker-assignment)
                          (:intent/capability intent)))
          :capability-not-granted

          (or (nil? (:intent/expires-at intent))
              (<= (:intent/expires-at intent) now-ms))
          :intent-expired)]
    (if reason
      {:intent/status :rejected :intent/reason reason}
      {:intent/status :admitted
       :intent/id (:intent/id intent)
       :intent/effect-status :not-executed
       :intent/next-gates
       [:incarnation-lease :capability :authority :homeostasis :hil]})))

(defn workplace-root [state-root]
  (io/file state-root "workplace"))

(defn inbox-directory [state-root]
  (io/file (workplace-root state-root) "inbox"))

(defn receipt-directory [state-root]
  (io/file (workplace-root state-root) "receipts"))

(defn lock-directory [state-root]
  (io/file (workplace-root state-root) "locks"))

(defn- safe-id [value]
  (let [value (str value)]
    (when-not (re-matches #"[A-Za-z0-9._:-]{1,160}" value)
      (throw (ex-info "Invalid workplace intent id"
                      {:type :workplace/invalid-intent-id :id value})))
    value))

(defn- read-edn-file [file]
  (try
    (edn/read-string (slurp file))
    (catch Exception error
      (throw (ex-info "Workplace EDN is unreadable"
                      {:type :workplace/invalid-edn
                       :file (.getCanonicalPath file)}
                      error)))))

(defn- atomic-write! [file value]
  (let [parent (.getParentFile file)
        temporary (io/file parent (str "." (.getName file) "."
                                        (UUID/randomUUID) ".tmp"))]
    (.mkdirs parent)
    (spit temporary (str (pr-str value) "\n"))
    (Files/move (.toPath temporary) (.toPath file)
                (into-array StandardCopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    value))

(defn- receipt-file [state-root intent-id]
  (io/file (receipt-directory state-root) (str (safe-id intent-id) ".edn")))

(defn- read-receipt [state-root intent-id]
  (let [file (receipt-file state-root intent-id)]
    (when (.isFile file) (read-edn-file file))))

(defn- write-receipt!
  [state-root envelope changes now-ms]
  (let [intent-id (:intent/id envelope)
        previous (or (read-receipt state-root intent-id)
                     {:receipt/schema receipt-schema
                      :receipt/id (str "receipt-" (UUID/randomUUID))
                      :receipt/worker (:intent/worker envelope)
                      :receipt/organization (:intent/organization envelope)
                      :receipt/intent intent-id
                      :receipt/capability (:intent/capability envelope)
                      :receipt/payload-hash (:intent/payload-hash envelope)
                      :receipt/parent (:intent/parent envelope)
                      :receipt/created-at now-ms})]
    (atomic-write!
     (receipt-file state-root intent-id)
     (merge previous changes {:receipt/updated-at now-ms}))))

(defn inbox
  "Read complete private envelopes in deterministic admission order."
  [state-root]
  (let [directory (inbox-directory state-root)]
    (->> (or (.listFiles directory) (make-array java.io.File 0))
         (filter #(and (.isFile %)
                       (str/ends-with? (.getName %) ".edn")))
         (map read-edn-file)
         (sort-by (juxt :intent/received-at :intent/id))
         vec)))

(defn- latest-decisions [envelopes now-ms]
  (->> envelopes
       (filter #(and (= :approval/submit (:intent/capability %))
                     (> (long (or (:intent/expires-at %) 0)) now-ms)
                     (#{:approved :rejected}
                      (get-in % [:intent/payload :decision]))
                     (not (str/blank? (str (:intent/parent %))))))
       (sort-by (juxt :intent/received-at :intent/id))
       (reduce
        (fn [index envelope]
          (assoc index (:intent/parent envelope)
                 {:decision (get-in envelope [:intent/payload :decision])
                  :approval-intent (:intent/id envelope)
                  :issued-by (:intent/issued-by envelope)}))
        {})))

(defn- effect-terminal? [receipt]
  (contains? terminal-effects (:receipt/effect-status receipt)))

(defn- with-intent-lock
  [state-root intent-id f]
  (let [directory (lock-directory state-root)
        file (io/file directory (str (safe-id intent-id) ".lock"))]
    (.mkdirs directory)
    (with-open [channel
                (FileChannel/open
                 (.toPath file)
                 (into-array StandardOpenOption
                             [StandardOpenOption/CREATE
                              StandardOpenOption/WRITE]))]
      (try
        (if-let [lock (.tryLock channel)]
          (try (f) (finally (.release lock)))
          {:intent/id intent-id :workplace/status :leased-elsewhere})
        (catch Exception error
          ;; Babashka does not expose OverlappingFileLockException as a
          ;; resolvable class even though FileChannel may throw it.
          (if (= "java.nio.channels.OverlappingFileLockException"
                 (.getName (class error)))
            {:intent/id intent-id :workplace/status :leased-elsewhere}
            (throw error)))))))

(defn- refuse!
  [state-root envelope reason now-ms]
  (write-receipt!
   state-root envelope
   {:receipt/status :completed
    :receipt/effect-status :refused
    :receipt/reason reason}
   now-ms))

(defn- process-approval!
  [state-root envelope now-ms]
  (write-receipt!
   state-root envelope
   {:receipt/status :completed
    :receipt/effect-status :succeeded
    :receipt/decision (get-in envelope [:intent/payload :decision])
    :receipt/evidence
    {:approval/parent (:intent/parent envelope)
     :approval/issued-by (:intent/issued-by envelope)}}
   now-ms))

(defn- process-effect!
  [state-root assignment envelope decision gate-fn dispatch-fn now-ms]
  (let [intent-id (:intent/id envelope)
        capability (:intent/capability envelope)
        approval-required? (= :intent/submit capability)]
    (cond
      (<= (long (or (:intent/expires-at envelope) 0)) now-ms)
      (write-receipt!
       state-root envelope
       {:receipt/status :completed :receipt/effect-status :expired
        :receipt/reason :intent-expired}
       now-ms)

      (not= :admitted
            (:intent/status (intent-decision assignment envelope now-ms)))
      (refuse! state-root envelope :assignment-gate now-ms)

      (and approval-required? (nil? decision))
      (write-receipt!
       state-root envelope
       {:receipt/status :awaiting-approval
        :receipt/effect-status :not-executed}
       now-ms)

      (and approval-required? (= :rejected (:decision decision)))
      (refuse! state-root envelope :human-rejected now-ms)

      :else
      (let [gate (gate-fn envelope)]
        (if-not (:admitted? gate)
          (write-receipt!
           state-root envelope
           {:receipt/status :deferred
            :receipt/effect-status :not-executed
            :receipt/reason (:reason gate)
            :receipt/gates gate}
           now-ms)
          (do
            ;; This durable transition plus the OS file lock makes concurrent
            ;; reconciliation observable and prevents one process from
            ;; dispatching the same envelope twice.
            (write-receipt!
             state-root envelope
             {:receipt/status :executing
              :receipt/effect-status :executing
              :receipt/gates gate
              :receipt/approval decision}
             now-ms)
            (try
              (let [effect (dispatch-fn envelope)]
                (write-receipt!
                 state-root envelope
                 {:receipt/status :completed
                  :receipt/effect-status :succeeded
                  :receipt/evidence effect}
                 (System/currentTimeMillis)))
              (catch Exception error
                (write-receipt!
                 state-root envelope
                 {:receipt/status :completed
                  :receipt/effect-status :failed
                  :receipt/reason :dispatch-failed
                  :receipt/error
                  (select-keys (ex-data error)
                               [:type :reason :run-id :loop-id])}
                 (System/currentTimeMillis))))))))))

(defn reconcile!
  "Consume a bounded batch of private workplace intents.

  `gate-fn` evaluates Tamaki-local lease, authority and homeostasis state.
  `dispatch-fn` is the only effect boundary. Both are injected so the file
  protocol remains deterministic and testable."
  [{:keys [state-root assignment now-ms gate-fn dispatch-fn limit]
    :or {now-ms (System/currentTimeMillis)
         gate-fn (constantly {:admitted? false :reason :gate-unconfigured})
         dispatch-fn (fn [_]
                       (throw (ex-info "dispatch is unconfigured"
                                       {:type :workplace/no-dispatch})))
         limit default-limit}}]
  (let [envelopes (inbox state-root)
        decisions (latest-decisions envelopes now-ms)
        candidates (take (max 1 (long limit)) envelopes)]
    {:workplace/schema "kotoba.tamaki.workplace-reconcile.v1"
     :workplace/observed (count envelopes)
     :workplace/results
     (mapv
      (fn [envelope]
        (with-intent-lock
          state-root (:intent/id envelope)
          (fn []
            (let [receipt (read-receipt state-root (:intent/id envelope))]
              (cond
                (effect-terminal? receipt)
                {:intent/id (:intent/id envelope)
                 :workplace/status :already-terminal
                 :effect/status (:receipt/effect-status receipt)}

                (= :approval/submit (:intent/capability envelope))
                (process-approval! state-root envelope now-ms)

                :else
                (process-effect!
                 state-root assignment envelope
                 (get decisions (:intent/id envelope))
                 gate-fn dispatch-fn now-ms))))))
      candidates)}))
