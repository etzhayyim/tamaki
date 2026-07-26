(ns kotoba.tamaki.runners-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.runners :as runners]))

(deftest built-in-runner-pool-is-explicit-and-non-secret
  (is (= #{"codex" "claude" "claude-zai" "grok"}
         (set (map :id (runners/profiles)))))
  (is (= "claude-zai:" (:model (runners/profile "claude-zai"))))
  (is (nil? (:env (runners/safe-profile
                   {:id "account-a" :env {"CLAUDE_CONFIG_DIR" "/secret"}})))))

(deftest swarm-worktrees-are-isolated-by-runner
  (is (not= (runners/worktree-path "/tmp/repo" "s1" "claude-a")
            (runners/worktree-path "/tmp/repo" "s1" "claude-b"))))

(deftest selected-tolerates-comma-separated-whitespace-and-blank-entries
  (testing "a human-typed --runners value with a space after the comma still resolves"
    (is (= ["codex" "claude"]
           (mapv :id (runners/selected "codex, claude")))))
  (testing "surrounding whitespace and a stray double comma are tolerated"
    (is (= ["codex" "claude"]
           (mapv :id (runners/selected " codex ,, claude "))))))
