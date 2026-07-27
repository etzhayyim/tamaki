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
(def default-integration-frontier-size 8)

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

      ;; A real linked worktree has a `.git` file pointing at the canonical
      ;; repository. A directory here is an independent repository that merely
      ;; shares Tamaki's generated naming convention; never remove it through
      ;; the source repository's worktree command.
      (.isDirectory (io/file project ".git"))
      {:maintenance/disposition :preserve
       :maintenance/source source
       :maintenance/project project
       :maintenance/run (:agent.run/id run)
       :maintenance/reason :independent-repository}

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

(defn- changed-path [porcelain-line]
  (let [path (if (> (count porcelain-line) 3)
               (subs porcelain-line 3)
               porcelain-line)]
    (last (str/split path #" -> "))))

(defn- evidence-key [item]
  (if-let [head (:maintenance/head item)]
    [:commit head]
    [:paths (->> (:maintenance/paths item)
                 (map changed-path)
                 sort
                 vec)]))

(defn- source-path? [path]
  (not (or (str/includes? path "/.cpcache/")
           (str/starts-with? path ".cpcache/")
           (str/ends-with? path ".basis")
           (str/ends-with? path ".cp")
           (str/ends-with? path ".manifest"))))

(defn integration-frontier
  "Collapse equivalent preserved outputs and rank the smallest review frontier.
  Unique commits come first, followed by dirty trees containing source changes;
  cache-only evidence remains counted but cannot starve useful work."
  ([plan] (integration-frontier plan default-integration-frontier-size))
  ([plan limit]
   (->> plan
        (filter #(= :preserve (:maintenance/disposition %)))
        (group-by evidence-key)
        (map (fn [[evidence items]]
               (let [representative (first (sort-by :maintenance/project items))
                     paths (mapv changed-path
                                 (:maintenance/paths representative))
                     source? (or (= :unique-commit
                                    (:maintenance/reason representative))
                                 (some source-path? paths))]
                 (merge
                  (select-keys representative
                               [:maintenance/run :maintenance/project
                                :maintenance/source :maintenance/reason
                                :maintenance/head])
                  {:maintenance/evidence evidence
                   :maintenance/paths paths
                   :maintenance/duplicates (dec (count items))
                   :maintenance/source-change? (boolean source?)}))))
        (sort-by (juxt #(if (= :unique-commit (:maintenance/reason %)) 0 1)
                       #(if (:maintenance/source-change? %) 0 1)
                       #(str (:maintenance/project %))))
        (take limit)
        vec)))

(defn summary [plan]
  (let [preserved (filter #(= :preserve (:maintenance/disposition %)) plan)
        groups (count (group-by evidence-key preserved))]
    {:maintenance/candidates (count plan)
     :maintenance/dispositions
     (frequencies (map :maintenance/disposition plan))
     :maintenance/conflicts
     (mapv #(select-keys % [:maintenance/run :maintenance/project
                            :maintenance/conflicts])
           (filter #(= :conflict (:maintenance/disposition %)) plan))
     :maintenance/preserved-count (count preserved)
     :maintenance/evidence-groups groups
     :maintenance/duplicate-evidence (- (count preserved) groups)
     :maintenance/integration-frontier (integration-frontier plan)
     ;; Full evidence remains available from `maintenance status`, but resident
     ;; actors consume the bounded frontier above rather than an ever-growing
     ;; prompt.
     :maintenance/preserved
     (mapv #(select-keys % [:maintenance/run :maintenance/project
                            :maintenance/reason :maintenance/paths
                            :maintenance/head])
           preserved)}))

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
