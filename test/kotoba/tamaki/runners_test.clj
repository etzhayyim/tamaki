(ns kotoba.tamaki.runners-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.tamaki.runners :as runners]))

(deftest built-in-runner-pool-is-explicit-and-non-secret
  (is (= #{"codex" "claude" "claude-zai"}
         (set (map :id (runners/profiles)))))
  (is (= "claude-zai:" (:model (runners/profile "claude-zai"))))
  (is (nil? (:env (runners/safe-profile
                   {:id "account-a" :env {"CLAUDE_CONFIG_DIR" "/secret"}})))))

(deftest swarm-worktrees-are-isolated-by-runner
  (is (not= (runners/worktree-path "/tmp/repo" "s1" "claude-a")
            (runners/worktree-path "/tmp/repo" "s1" "claude-b"))))
