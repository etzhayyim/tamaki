(ns kotoba.tamaki.cli
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [kotoba.tamaki.adapters :as adapters]
            [kotoba.tamaki.model :as model]
            [kotoba.tamaki.store :as store])
  (:gen-class))

(defn now [] (System/currentTimeMillis))

(defn usage []
  (str "tamaki — one CLI for Kotoba agent execution\n\n"
       "Usage:\n"
       "  tamaki submit <goal> --project PATH [--mode local|fleet] [options]\n"
       "  tamaki run <run-id>\n"
       "  tamaki status [run-id]\n"
       "  tamaki resume <run-id>\n"
       "  tamaki agents [run-id]\n"
       "  tamaki nodes [fleet-nodes options]\n"
       "  tamaki tick [fleet-tick options]\n"
       "  tamaki infer <probe|plan|up|down|ps|serve|generate> ...\n"
       "  tamaki murakumo <command> ...\n"
       "  tamaki doctor\n"
       "  tamaki contract\n\n"
       "Options:\n"
       "  --repo OWNER/REPO --pin SHA --node NAME|auto --model MODEL\n"
       "  --requires git,nbb,clojure --parent RUN-ID --execute\n"))

(defn parse-args
  [args]
  (loop [xs args positional [] options {}]
    (if (empty? xs)
      {:positional positional :options options}
      (let [[x y & more] xs]
        (if (str/starts-with? x "--")
          (if (= x "--execute")
            (recur (rest xs) positional (assoc options :execute true))
            (recur more positional
                   (assoc options (keyword (subs x 2)) y)))
          (recur (rest xs) (conj positional x) options))))))

(defn events [] (store/read-events (store/default-root)))
(defn runs [] (model/fold-events (events)))
(defn run-by-id [id] (get (runs) id))

(defn emit! [run kind data]
  (store/append-event! (store/default-root)
                       (model/event run kind (now) data)))

(defn print-edn [x]
  (pprint/pprint x))

(declare execute-run!)

(defn submit!
  [{:keys [positional options]}]
  (let [goal (first positional)
        mode (keyword (or (:mode options) "local"))
        requires (if-let [r (:requires options)]
                   (set (map keyword (remove str/blank? (str/split r #","))))
                   (if (= mode :fleet) #{:git :nbb} #{:git}))
        run (model/agent-run
             {:goal goal
              :project (:project options)
              :repo (:repo options)
              :pin (:pin options)
              :mode mode
              :node (keyword (or (:node options) "auto"))
              :model (:model options)
              :capabilities requires
              :parent (:parent options)}
             (now))]
    (when (and (= mode :local) (str/blank? (:agent.run/project run)))
      (throw (ex-info "Local mode requires --project PATH" {})))
    (when (and (= mode :fleet)
               (some str/blank? [(:agent.run/repo run) (:agent.run/pin run)]))
      (throw (ex-info "Fleet mode requires --repo OWNER/REPO and --pin SHA" {})))
    (store/append-event!
     (store/default-root)
     (model/event run :run/submitted (now) {:run run}))
    (print-edn run)
    (when (:execute options)
      (execute-run! run))))

(defn write-work! [run]
  (let [dir (io/file (store/default-root) "work")
        f (io/file dir (str (:agent.run/id run) ".edn"))]
    (.mkdirs dir)
    (spit f (pr-str (adapters/fleet-work run)))
    (.getAbsolutePath f)))

(defn execute-run!
  [run]
  (when-not run
    (throw (ex-info "Unknown run" {})))
  (when (model/terminal-statuses (:agent.run/status run))
    (throw (ex-info "Terminal run cannot execute"
                    {:run-id (:agent.run/id run)
                     :status (:agent.run/status run)})))
  (let [report (adapters/readiness)
        mode (:agent.run/mode run)]
    (when-not (adapters/ready-for? mode report)
      (throw (ex-info "Runtime is not ready" {:mode mode :doctor report})))
    (let [leased (model/transition run :leased (now)
                                   {:agent.run/worker
                                    (or (System/getenv "TAMAKI_WORKER_ID")
                                        (.getHostName (java.net.InetAddress/getLocalHost)))})
          _ (emit! run :run/leased
                   (select-keys leased [:agent.run/worker :agent.run/node]))
          argv (if (= mode :fleet)
                 (adapters/fleet-command leased (write-work! leased))
                 (adapters/local-command leased))
          _ (emit! leased :run/started {:agent.run/command argv})
          exit (adapters/execute! argv
                                  (when (= mode :fleet)
                                    (adapters/sibling "kotoba-fleet")))
          kind (if (zero? exit) :run/succeeded :run/failed)
          result {:agent.run/exit exit
                  :agent.run/command argv}]
      (emit! (assoc leased :agent.run/status :running) kind result)
      (print-edn (assoc result :agent.run/id (:agent.run/id run)
                       :agent.run/status (if (zero? exit) :succeeded :failed)))
      exit)))

(defn status!
  [id]
  (if id
    (print-edn (or (run-by-id id)
                   (throw (ex-info "Unknown run" {:run-id id}))))
    (print-edn (->> (vals (runs))
                    (sort-by :agent.run/updated-at >)
                    (mapv #(select-keys %
                                        [:agent.run/id :agent.run/status
                                         :agent.run/mode :agent.run/node
                                         :agent.run/goal :agent.run/updated-at]))))))

(defn resume!
  [id]
  (let [run (run-by-id id)]
    (when-not (and run (model/resumable? run))
      (throw (ex-info "Run is not resumable"
                      {:run-id id :status (:agent.run/status run)})))
    (when (= :local (:agent.run/mode run))
      (let [exit (adapters/execute!
                  (adapters/local-resume-command run))]
        (when-not (zero? exit)
          (throw (ex-info "Underlying agent runtime could not resume"
                          {:run-id id :exit exit})))))
    (emit! run :run/requeued {:agent.run/resume-from (:agent.run/status run)})
    (execute-run! (run-by-id id))))

(defn agents!
  [root-id]
  (let [all (vals (runs))
        children (group-by :agent.run/parent all)
        roots (if root-id
                [(run-by-id root-id)]
                (get children nil))]
    (letfn [(tree [run]
              {:run (select-keys run [:agent.run/id :agent.run/status
                                      :agent.run/goal :agent.run/node])
               :children (mapv tree (get children (:agent.run/id run)))})]
      (print-edn (mapv tree (remove nil? roots))))))

(defn doctor! []
  (let [report (adapters/readiness)]
    (print-edn report)
    (if (every? :ok? (vals report)) 0 1)))

(defn passthrough!
  [argv cwd]
  (adapters/execute! argv cwd))

(defn dispatch
  [args]
  (let [[command & rest] args
        parsed (parse-args rest)]
    (case command
      "submit" (or (submit! parsed) 0)
      "run" (execute-run! (run-by-id (first (:positional parsed))))
      "status" (do (status! (first (:positional parsed))) 0)
      "resume" (resume! (first (:positional parsed)))
      "agents" (do (agents! (first (:positional parsed))) 0)
      "nodes" (passthrough! (adapters/fleet-tool-command "nodes" rest)
                            (adapters/sibling "kotoba-fleet"))
      "tick" (passthrough! (adapters/fleet-tool-command "tick" rest)
                           (adapters/sibling "kotoba-fleet"))
      "infer" (passthrough! (adapters/murakumo-command :infer rest)
                            (adapters/sibling "murakumo"))
      "murakumo" (passthrough! (adapters/murakumo-command :core rest)
                               (adapters/sibling "murakumo"))
      "doctor" (doctor!)
      "contract" (do (print-edn {:version model/contract-version
                                  :transitions model/transitions})
                     0)
      (do (println (usage)) (if (or (nil? command) (= command "help")) 0 2)))))

(defn -main [& args]
  (try
    (let [exit (dispatch args)]
      (when (and (number? exit) (not (zero? exit)))
        (System/exit exit)))
    (catch Exception e
      (binding [*out* *err*]
        (println "tamaki:" (.getMessage e))
        (when-let [data (ex-data e)] (print-edn data)))
      (System/exit 2))))
