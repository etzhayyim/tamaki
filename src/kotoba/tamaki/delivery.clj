(ns kotoba.tamaki.delivery
  "Injectable process boundary and pure helpers for sovereign Radicle delivery."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:dynamic *process-fn*
  (fn [argv cwd]
    (let [pb (ProcessBuilder. ^java.util.List argv)
          _ (when cwd (.directory pb (io/file cwd)))
          process (.start pb)
          out (future (slurp (.getInputStream process)))
          err (future (slurp (.getErrorStream process)))
          exit (.waitFor process)]
      {:exit exit :out @out :err @err})))

(defn execute!
  ([argv] (execute! argv nil))
  ([argv cwd]
   (let [result (*process-fn* (vec argv) cwd)]
     (if (map? result)
       (merge {:exit 0 :out "" :err ""} result)
       {:exit result :out "" :err ""}))))

(defn succeeded! [result operation]
  (when-not (zero? (:exit result))
    (throw (ex-info (str operation " failed")
                    {:operation operation :exit (:exit result)})))
  result)

(defn output-id [result]
  (or (some->> (str (:out result) "\n" (:err result))
               (re-find #"(?:rad:)?[0-9a-f]{40}")
               str)
      (some-> (:out result) str/trim not-empty)))

(defn issue-create-command [title description]
  (cond-> ["rad" "issue" "--no-announce" "open" "--title" title]
    (not (str/blank? description)) (conj "--description" description)))

(defn issue-show-command [issue-id]
  ["rad" "issue" "show" issue-id])

(defn issue-list-command []
  ["rad" "issue" "list" "--open"])

(defn issue-solve-command [issue-id]
  ["rad" "issue" "state" "--no-announce" "--solved" issue-id])

(defn issue-close-command [issue-id]
  ["rad" "issue" "state" "--no-announce" "--closed" issue-id])

(defn git-status-command [] ["git" "status" "--porcelain"])
(defn porcelain-path [line]
  (let [path (subs line (min 3 (count line)))]
    (if-let [[_ destination] (re-matches #".* -> (.+)" path)]
      destination
      path)))

(defn porcelain-paths [output]
  (->> (str/split-lines (or output ""))
       (remove str/blank?)
       (mapv porcelain-path)))

(defn git-add-command [paths] (into ["git" "add" "--"] paths))
(defn git-commit-command [message] ["git" "commit" "-m" message])
(defn git-head-command [] ["git" "rev-parse" "HEAD"])
(defn git-ancestor-command [ancestor descendant]
  ["git" "merge-base" "--is-ancestor" ancestor descendant])
(defn patch-create-command [title]
  ["git" "-c" "core.editor=true" "push"
   "-o" "patch.draft"
   "-o" "no-sync"
   "-o" (str "patch.message=" title)
   "rad" "HEAD:refs/patches"])

(defn patch-show-command [patch-id]
  ["rad" "patch" "show" patch-id])

(defn patch-diff-command [patch-id]
  ["rad" "patch" "diff" patch-id])

(defn patch-accept-command [patch-id evidence]
  ["rad" "patch" "--no-announce" "review" patch-id
   "--accept" "--message" evidence])

(defn git-switch-command [branch] ["git" "switch" branch])
(defn git-merge-patch-command [patch-id]
  ["git" "merge" "--ff-only" (str "rad/patches/" patch-id)])
(defn push-canonical-command [branch]
  ["git" "push" "-o" "no-sync" "rad" branch])

(defn public-result [result]
  (select-keys result [:exit :out :err]))
