(ns kotoba.tamaki.runners
  "Non-secret runner profiles for concurrent subscription-backed workers."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- claude-accounts []
  (->> (str/split (or (System/getenv "TAMAKI_CLAUDE_ACCOUNTS") "") #",")
       (keep (fn [entry]
               (let [[id dir] (str/split entry #"=" 2)]
                 (when (and (seq id) (seq dir))
                   {:id id :model "claude:sonnet"
                    :env {"CLAUDE_CONFIG_DIR" dir}
                    :kind :claude-account}))))
       vec))

(defn profiles []
  (let [defaults [{:id "codex" :model "codex:" :kind :codex}
                  {:id "claude" :model "claude:sonnet" :kind :claude}
                  {:id "claude-zai" :model "claude-zai:" :kind :claude-zai}]
        file (io/file (or (System/getenv "TAMAKI_RUNNERS_FILE")
                          (str (System/getProperty "user.home")
                               "/.config/tamaki/runners.edn")))
        configured (if (.isFile file) (edn/read-string (slurp file)) [])]
    (->> (concat defaults (claude-accounts) configured)
         (reduce (fn [result profile] (assoc result (:id profile) profile)) {})
         vals
         (sort-by :id)
         vec)))

(defn profile [id]
  (or (some #(when (= id (:id %)) %) (profiles))
      (throw (ex-info "Unknown runner profile"
                      {:runner id :available (mapv :id (profiles))}))))

(defn selected [csv]
  (mapv profile
        (if (str/blank? csv)
          (map :id (profiles))
          (remove str/blank? (str/split csv #",")))))

(defn safe-profile [profile]
  (dissoc profile :env))

(defn worktree-path [project swarm-id runner-id]
  (let [repo (io/file project)
        parent (.getParentFile repo)]
    (.getAbsolutePath
     (io/file parent
              (str "." (.getName repo) "-tamaki-" swarm-id "-" runner-id)))))

(defn prepare-worktree! [project swarm-id runner-id]
  (let [target (worktree-path project swarm-id runner-id)
        builder (doto (ProcessBuilder.
                       ^java.util.List
                       ["git" "-C" project "worktree" "add"
                        "--detach" target "HEAD"])
                  (.inheritIO))
        exit (.waitFor (.start builder))]
    (when-not (zero? exit)
      (throw (ex-info "Could not create isolated runner worktree"
                      {:runner runner-id :project project :target target
                       :exit exit})))
    target))
