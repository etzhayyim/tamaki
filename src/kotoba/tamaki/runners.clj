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
                  ;; The claude.ai subscription session used by claude-zai
                  ;; must not inherit API authentication from the supervisor.
                  ;; Claude gives API auth precedence and otherwise disables
                  ;; the subscription connectors.
                  {:id "claude-zai" :model "claude-zai:" :kind :claude-zai
                   :unset-env ["ANTHROPIC_API_KEY"
                               "ANTHROPIC_AUTH_TOKEN"]}
                  {:id "grok" :model "grok:" :kind :grok}]
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
  ;; Human-typed --runners values commonly include a space after the comma
  ;; ("codex, claude") or a stray double comma; trim before filtering blanks
  ;; so those still resolve instead of failing on a space-padded runner id.
  (mapv profile
        (if (str/blank? csv)
          (map :id (profiles))
          (->> (str/split csv #",")
               (map str/trim)
               (remove str/blank?)))))

(defn safe-profile [profile]
  (dissoc profile :env))

(defn worktree-path [project swarm-id runner-id]
  (let [repo (io/file project)
        parent (.getParentFile repo)]
    (.getAbsolutePath
     (io/file parent
              (str "." (.getName repo) "-tamaki-" swarm-id "-" runner-id)))))

(defn available-worktree-path [project swarm-id runner-id]
  (let [base (worktree-path project swarm-id runner-id)]
    (if-not (.exists (io/file base))
      base
      (first
       (for [suffix (range 2 10000)
             :let [candidate (str base "-" suffix)]
             :when (not (.exists (io/file candidate)))]
         candidate)))))

(defn prepare-worktree! [project swarm-id runner-id]
  (let [target (available-worktree-path project swarm-id runner-id)
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

(defn ensure-run-worktree!
  "Reuse a configured run's live isolated worktree after supervisor recovery.
  Re-preparing from `:agent.run/project` would nest one generated worktree
  inside another on every restart, consuming storage and losing source scope."
  [run actor-token]
  (let [configured (:agent.run/project run)
        source (or (:agent.run/source-project run) configured)]
    (if (and (:agent.run/source-project run)
             (.isDirectory (io/file configured)))
      configured
      (prepare-worktree!
       source actor-token
       (str (:agent.run/runner run) "-" (:agent.run/replica run))))))
