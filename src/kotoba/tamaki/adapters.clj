(ns kotoba.tamaki.adapters
  "Command adapters for the existing Kotoba runtimes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kotoba.tamaki.store :as store]))

(defn workspace-root []
  (or (System/getenv "TAMAKI_WORKSPACE_ROOT")
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
    (zero? (.waitFor (.start (ProcessBuilder. ["sh" "-c"
                                               (str "command -v " command " >/dev/null 2>&1")]))))
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

(defn readiness []
  (let [kc (str (io/file (sibling "kotoba-code") "bin" "kotoba-code"))
        fleet (str (io/file (sibling "kotoba-fleet") "bin"
                            "fleet-sandbox-dispatch.cljs"))
        murakumo (sibling "murakumo")]
    {:tamaki {:ok? true}
     :bb {:ok? (command-exists? "bb")}
     :nbb {:ok? (command-exists? "nbb")}
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
                 [:bb :kotoba-code]))))

(def ^:dynamic *execute-fn*
  (fn [argv cwd]
    (let [pb (doto (ProcessBuilder. ^java.util.List argv)
               (.inheritIO))
          _ (when cwd (.directory pb (io/file cwd)))
          p (.start pb)]
      (.waitFor p))))

(defn execute!
  ([argv] (execute! argv nil))
  ([argv cwd]
   (*execute-fn* argv cwd)))
