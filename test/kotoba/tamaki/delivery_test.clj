(ns kotoba.tamaki.delivery-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.delivery :as delivery]))

(deftest radicle-command-contracts
  (is (= ["rad" "issue" "--no-announce" "open" "--title" "T"
          "--description" "D"]
         (delivery/issue-create-command "T" "D")))
  (is (= ["rad" "issue" "state" "--no-announce" "--solved" "issue-id"]
         (delivery/issue-solve-command "issue-id")))
  (is (= ["rad" "issue" "state" "--no-announce" "--closed" "issue-id"]
         (delivery/issue-close-command "issue-id")))
  (is (= ["git" "add" "--" "src/a.clj" "test/a_test.clj"]
         (delivery/git-add-command ["src/a.clj" "test/a_test.clj"])))
  (is (= ["git" "merge" "--ff-only"
          "rad/patches/0123456789012345678901234567890123456789"]
         (delivery/git-merge-patch-command
          "0123456789012345678901234567890123456789")))
  (is (= ["git" "push" "-o" "no-sync" "rad" "main"]
         (delivery/push-canonical-command "main")))
  (is (= ["git" "merge-base" "--is-ancestor" "a" "b"]
         (delivery/git-ancestor-command "a" "b"))))

(deftest porcelain-paths-are-explicit
  (is (= ["src/a.clj" "test/b.clj" "new.clj"]
         (delivery/porcelain-paths
          " M src/a.clj\nM  test/b.clj\n?? new.clj\n"))))

(deftest process-boundary-is-injectable
  (binding [delivery/*process-fn*
            (fn [argv cwd] {:exit 0 :out (pr-str [argv cwd])})]
    (is (= 0 (:exit (delivery/execute! ["rad" "."] "/repo"))))
    (is (re-find #"/repo" (:out (delivery/execute! ["rad" "."] "/repo"))))))

(deftest remaining-delivery-command-contracts
  (is (= ["rad" "issue" "show" "abc123"]
         (delivery/issue-show-command "abc123")))
  (is (= ["rad" "issue" "list" "--open"]
         (delivery/issue-list-command)))
  (is (= ["git" "status" "--porcelain"]
         (delivery/git-status-command)))
  (is (= ["git" "commit" "-m" "Add feature"]
         (delivery/git-commit-command "Add feature")))
  (is (= ["git" "rev-parse" "HEAD"]
         (delivery/git-head-command)))
  (is (= ["git" "switch" "main"]
         (delivery/git-switch-command "main")))
  (is (= ["rad" "patch" "show" "patch-1"]
         (delivery/patch-show-command "patch-1")))
  (is (= ["rad" "patch" "diff" "patch-1"]
         (delivery/patch-diff-command "patch-1")))
  (is (= ["rad" "patch" "--no-announce" "review" "patch-1"
          "--accept" "--message" "evidence"]
         (delivery/patch-accept-command "patch-1" "evidence")))
  (is (= ["git" "-c" "core.editor=true" "push"
          "-o" "patch.draft"
          "-o" "no-sync"
          "-o" "patch.message=Add feature"
          "rad" "HEAD:refs/patches"]
         (delivery/patch-create-command "Add feature"))))

(deftest issue-create-omits-blank-description
  (is (= ["rad" "issue" "--no-announce" "open" "--title" "T"]
         (delivery/issue-create-command "T" nil)))
  (is (= ["rad" "issue" "--no-announce" "open" "--title" "T"]
         (delivery/issue-create-command "T" "   "))))

(deftest output-id-extracts-patch-or-issue-identifier
  (testing "rad: prefixed 40-char SHA is matched verbatim"
    (is (= "rad:0123456789012345678901234567890123456789"
           (delivery/output-id
            {:out "rad:0123456789012345678901234567890123456789\n"}))))
  (testing "bare 40-char SHA is matched"
    (is (= "0123456789012345678901234567890123456789"
           (delivery/output-id
            {:out "0123456789012345678901234567890123456789"}))))
  (testing "stderr is searched when stdout has no SHA"
    (is (= "0123456789012345678901234567890123456789"
           (delivery/output-id
            {:out "" :err "patch 0123456789012345678901234567890123456789 created"}))))
  (testing "non-SHA trimmed stdout is the fallback identifier"
    (is (= "issue-id-1"
           (delivery/output-id {:out "  issue-id-1  \n"}))))
  (testing "empty output yields nil"
    (is (nil? (delivery/output-id {:out "" :err ""})))))

(deftest succeeded-passes-through-and-fails-closed
  (is (= {:exit 0 :out "ok"}
         (delivery/succeeded! {:exit 0 :out "ok"} "operation")))
  (is (thrown-with-msg? Exception #"operation failed"
                        (delivery/succeeded! {:exit 1 :out ""} "operation"))))

(deftest public-result-projects-only-exit-out-err
  (is (= {:exit 0 :out "o" :err "e"}
         (delivery/public-result
          {:exit 0 :out "o" :err "e" :secret "token" :extra 1}))))
