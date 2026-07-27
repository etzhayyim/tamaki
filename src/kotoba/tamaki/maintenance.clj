(ns kotoba.tamaki.maintenance
  "Deterministic lifecycle maintenance for generated git worktrees.

  Cleanup is deliberately fail-closed: only terminal runs with a clean,
  conflict-free worktree whose HEAD is already reachable from the canonical
  repository may be removed. Dirty trees and unique commits are evidence, not
  garbage."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kotoba.tamaki.delivery :as delivery]
            [kotoba.tamaki.model :as model]))

(def default-grace-ms 300000)

(defn generated-worktree? [source project]
  (when (and source project)
    (let [source-file (.getCanonicalFile (io/file source))
          project-file (.getCanonicalFile (io/file project))
          prefix (str "." (.getName source-file) "-tamaki-")]
      (and (not= source-file project-file)
           (= (.getParentFile source-file) (.getParentFile project-file))
           (str/starts-with? (.getName project-file) prefix)))))

(defn- command [argv cwd]
  (delivery/execute! argv cwd))

(defn inspect-run
  [run now-ms]
  (let [source (:agent.run/source-project run)
        project (:agent.run/project run)
        terminal? (contains? model/terminal-statuses (:agent.run/status run))
        old-enough? (>= (- now-ms (or (:agent.run/updated-at run) now-ms))
                        default-grace-ms)]
    (cond
      (not (generated-worktree? source project))
      {:maintenance/disposition :ignored
       :maintenance/reason :not-generated-worktree}

      (not (.isDirectory (io/file project)))
      {:maintenance/disposition :stale-registration
       :maintenance/reason :worktree-missing}

      (not terminal?)
      {:maintenance/disposition :active
       :maintenance/reason :run-not-terminal}

      (not old-enough?)
      {:maintenance/disposition :grace
       :maintenance/reason :recently-terminal}

      :else
      (let [status (command ["git" "-C" project "status" "--porcelain"
                             "--untracked-files=all"] source)
            conflicts (command ["git" "-C" project "diff" "--name-only"
                                "--diff-filter=U"] source)
            head (command ["git" "-C" project "rev-parse" "HEAD"] source)
            canonical-head (command ["git" "-C" source "rev-parse" "HEAD"]
                                    source)
            clean? (and (zero? (:exit status))
                        (str/blank? (:out status)))
            conflict-paths (->> (str/split-lines (:out conflicts))
                                (remove str/blank?) vec)
            reachable (when (and clean? (zero? (:exit head))
                                 (zero? (:exit canonical-head)))
                        (command ["git" "-C" source "merge-base"
                                  "--is-ancestor"
                                  (str/trim (:out head))
                                  (str/trim (:out canonical-head))]
                                 source))]
        (merge
         {:maintenance/source source
          :maintenance/project project
          :maintenance/run (:agent.run/id run)
          :maintenance/status (:agent.run/status run)
          :maintenance/conflicts conflict-paths}
         (cond
           (seq conflict-paths)
           {:maintenance/disposition :conflict
            :maintenance/reason :unmerged-paths}

           (not clean?)
           {:maintenance/disposition :preserve
            :maintenance/reason :dirty-worktree
            :maintenance/paths
            (->> (str/split-lines (:out status))
                 (remove str/blank?) vec)}

           (not (zero? (or (:exit reachable) 1)))
           {:maintenance/disposition :preserve
            :maintenance/reason :unique-commit
            :maintenance/head (str/trim (:out head))}

           :else
           {:maintenance/disposition :remove
            :maintenance/reason :clean-and-reachable}))))))

(defn inspect-source-conflict [source]
  (when (and source (.isDirectory (io/file source)))
    (let [result (command ["git" "-C" source "diff" "--name-only"
                           "--diff-filter=U"] source)
          paths (->> (str/split-lines (:out result))
                     (remove str/blank?) vec)
          lock (io/file source ".git" "index.lock")]
      (cond
        (seq paths)
        {:maintenance/disposition :conflict
         :maintenance/project source
         :maintenance/source source
         :maintenance/reason :canonical-unmerged-paths
         :maintenance/conflicts paths}

        (.isFile lock)
        {:maintenance/disposition :conflict
         :maintenance/project source
         :maintenance/source source
         :maintenance/reason :canonical-index-lock
         :maintenance/conflicts [(.getAbsolutePath lock)]}

        :else nil))))

(defn plan [runs now-ms]
  (let [priority {:conflict 6 :preserve 5 :active 4 :grace 3
                  :remove 2 :stale-registration 1}
        worktrees (map #(inspect-run % now-ms) runs)
        sources (->> runs (keep :agent.run/source-project) distinct
                     (keep inspect-source-conflict))]
    (->> (concat worktrees sources)
         (remove #(= :ignored (:maintenance/disposition %)))
         ;; A resumed/recovered run can refer to the same worktree more than
         ;; once. Execute at most one operation per path and choose the most
         ;; conservative observed disposition.
         (group-by #(or (:maintenance/project %)
                        (str "missing:" (:maintenance/run %))))
         vals
         (map (fn [items]
                (apply max-key
                       #(get priority (:maintenance/disposition %) 0)
                       items)))
         (sort-by (juxt :maintenance/disposition :maintenance/project))
         vec)))

(defn summary [plan]
  {:maintenance/candidates (count plan)
   :maintenance/dispositions
   (frequencies (map :maintenance/disposition plan))
   :maintenance/conflicts
   (mapv #(select-keys % [:maintenance/run :maintenance/project
                          :maintenance/conflicts])
         (filter #(= :conflict (:maintenance/disposition %)) plan))
   :maintenance/preserved
   (mapv #(select-keys % [:maintenance/run :maintenance/project
                          :maintenance/reason :maintenance/paths
                          :maintenance/head])
         (filter #(= :preserve (:maintenance/disposition %)) plan))})

(defn apply-plan! [plan]
  (mapv
   (fn [{:maintenance/keys [disposition source project] :as item}]
     (if (= :remove disposition)
       (let [removed (command ["git" "-C" source "worktree" "remove" project]
                              source)]
         (assoc item
                :maintenance/applied? (zero? (:exit removed))
                :maintenance/result
                (select-keys removed [:exit :out :err])))
       (assoc item :maintenance/applied? false)))
   plan))
