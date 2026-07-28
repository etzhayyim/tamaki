(ns kotoba.tamaki.adapters
  "Command adapters for the existing Kotoba runtimes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kotoba.tamaki.store :as store]))

(defn workspace-root []
  (or (System/getenv "TAMAKI_WORKSPACE_ROOT")
      (System/getenv "TAMAKI_WORKSPACE")
      (System/getenv "COM_JUNKAWASAKI_ROOT")
      (-> (io/file (System/getProperty "user.dir"))
          .getAbsoluteFile
          .getParentFile
          .getParentFile
          .getParent)))

(defn sibling
  [name]
  (str (io/file (workspace-root) "orgs" "kotoba-lang" name)))

(defn executable? [path]
  (let [f (io/file path)]
    (and (.exists f) (.canExecute f))))

(defn command-exists?
  [command]
  (try
    (zero? (.waitFor (.start (ProcessBuilder.
                              ["sh" "-c" "command -v -- \"$1\" >/dev/null 2>&1"
                               "tamaki-command-exists" (str command)]))))
    (catch Exception _ false)))

(defn local-command
  [run]
  (let [binary (str (io/file (sibling "kotoba-code") "bin" "kotoba-code"))
        base [binary]
        model (:agent.run/model run)]
    (cond-> (conj base
                  (:agent.run/goal run)
                  (:agent.run/project run))
      model (conj model))))

(defn local-resume-command
  [run]
  (let [binary (str (io/file (sibling "kotoba-code") "bin" "kotoba-code"))
        model (:agent.run/model run)]
    (cond-> [binary "--resume" (:agent.run/project run)]
      model (conj model))))

(defn fleet-work
  [run]
  {:work-id (:agent.run/id run)
   :unit (or (:agent.run/repo run) (:agent.run/project run))
   :repo (:agent.run/repo run)
   :pin (:agent.run/pin run)
   :node (name (:agent.run/node run))
   :requires (:agent.run/required-capabilities run)
   :ttl-ms (get-in run [:agent.run/budget :deadline-ms])
   :protected-paths [".git/" ".github/workflows/"]
   :budget (:agent.run/budget run)
   :prompt (:agent.run/goal run)})

(defn fleet-command
  [run work-file]
  (let [fleet (sibling "kotoba-fleet")
        cp (str (io/file fleet "src") ":"
                (io/file fleet "hosts" "nbb"))]
    ["nbb" "--classpath" cp
     (str (io/file fleet "bin" "fleet-sandbox-dispatch.cljs"))
     "--work" work-file
     "--agent" (str "tamaki-" (:agent.run/id run))
     "--node" (name (:agent.run/node run))
     "--materialize" "dry-run"]))

(defn fleet-tool-command
  [tool args]
  (let [fleet (sibling "kotoba-fleet")
        cp (str (io/file fleet "src") ":"
                (io/file fleet "hosts" "nbb"))]
    (into ["nbb" "--classpath" cp
           (str (io/file fleet "bin" (str "fleet-" tool ".cljs")))]
          args)))

(defn murakumo-command
  [surface args]
  (let [root (sibling "murakumo")
        main (case surface
               :infer "murakumo.infer"
               "murakumo.core")]
    (into ["bb" "-cp" (str (io/file root "src")) "-m" main] args)))

(def required-shared-namespaces
  "Namespaces bin/tamaki must resolve before any command runs.

  After capability extraction, a babashka -cp of only src + sibling hil
  fails at require time for kotoba.core.actor-capability. deps.edn pins
  (hil, kotoba-core-contracts) must be on the classpath."
  '[kotoba.core.actor-capability
    kotoba.core.capability-repository
    hil.core])

(defn shared-contract-ready?
  "True when every shared namespace required by the operator entrypoint is
  already loaded on this classpath."
  []
  (every? #(some? (find-ns %)) required-shared-namespaces))

(defn readiness []
  (let [kc (str (io/file (sibling "kotoba-code") "bin" "kotoba-code"))
        fleet (str (io/file (sibling "kotoba-fleet") "bin"
                            "fleet-sandbox-dispatch.cljs"))
        murakumo (sibling "murakumo")]
    {:tamaki {:ok? true}
     :bb {:ok? (command-exists? "bb")}
     :nbb {:ok? (command-exists? "nbb")}
     ;; bin/tamaki resolves deps.edn via `clojure -Spath` before launching bb.
     :clojure {:ok? (command-exists? "clojure")}
     :capability-contract {:ok? (shared-contract-ready?)
                           :namespaces required-shared-namespaces}
     :kotoba-code {:ok? (executable? kc) :path kc}
     :kotoba-fleet {:ok? (.exists (io/file fleet)) :path fleet}
     :murakumo {:ok? (.exists (io/file murakumo)) :path murakumo}
     :event-store (store/readiness)}))

(defn ready-for?
  [mode report]
  (every? :ok?
          (map report
               (case mode
                 :fleet [:nbb :kotoba-fleet :murakumo]
                 ;; :external runs a deterministic command the caller supplies
                 ;; (an ingest tick, a scheduled report). It needs the durable
                 ;; event store and nothing else -- gating it on kotoba-code
                 ;; would demand a model that is not in the loop.
                 :external [:tamaki :event-store]
                 [:bb :kotoba-code]))))

(def ^:dynamic *process-env* {})
(def ^:dynamic *unset-process-env* [])
(def ^:dynamic *activity-fn* (fn [_] nil))

(defn- process-descendants [process]
  (with-open [stream (.descendants (.toHandle process))]
    (vec (iterator-seq (.iterator stream)))))

(defn- stop-process-handles! [handles]
  (doseq [handle handles
          :when (.isAlive handle)]
    (.destroy handle))
  (Thread/sleep 100)
  (doseq [handle handles
          :when (.isAlive handle)]
    (.destroyForcibly handle)))

(defn- daemon-thread [name f]
  (doto (Thread. ^Runnable f name)
    (.setDaemon true)
    (.start)))

(def ^:dynamic *execute-fn*
  (fn [argv cwd]
    (let [pb (doto (ProcessBuilder. ^java.util.List argv)
               (.redirectErrorStream true))
          _ (doseq [[key value] *process-env*]
              (.put (.environment pb) key value))
          _ (doseq [key *unset-process-env*]
              (.remove (.environment pb) key))
          _ (when cwd (.directory pb (io/file cwd)))
          p (.start pb)
          tracking? (atom true)
          descendants (atom #{})
          tracker
          (daemon-thread
           "tamaki-process-descendants"
           #(while (and @tracking? (.isAlive p))
              (swap! descendants into (process-descendants p))
              (Thread/sleep 100)))
          output
          (daemon-thread
           "tamaki-process-output"
           #(with-open [reader (io/reader (.getInputStream p))]
              (doseq [line (line-seq reader)]
                (println line)
                (*activity-fn* line))))]
      (try
        (let [exit (.waitFor p)]
          ;; AgentRun subprocesses are bounded cells. Backend grandchildren
          ;; must not survive a timed-out or failed kotoba-code parent as
          ;; orphaned token consumers.
          (reset! tracking? false)
          (.join tracker 1000)
          (stop-process-handles! @descendants)
          (.join output 2000)
          exit)
        (finally
          (reset! tracking? false)
          (stop-process-handles! @descendants))))))

(defn execute!
  ([argv] (execute! argv nil))
  ([argv cwd]
   (*execute-fn* argv cwd)))
