(ns kotoba.tamaki.delivery-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.tamaki.delivery :as delivery]))

(deftest radicle-command-contracts
  (is (= ["rad" "issue" "--no-announce" "open" "--title" "T"
          "--description" "D"]
         (delivery/issue-create-command "T" "D")))
  (is (= ["rad" "issue" "state" "--no-announce" "--solved" "issue-id"]
         (delivery/issue-solve-command "issue-id")))
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
