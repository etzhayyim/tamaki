(ns kotoba.tamaki.maintenance-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.delivery :as delivery]
            [kotoba.tamaki.maintenance :as maintenance]))

(def terminal-run
  {:agent.run/id "run-1"
   :agent.run/source-project "/repo/tamaki"
   :agent.run/project "/repo/.tamaki-tamaki-actor-codex-1"
   :agent.run/status :succeeded
   :agent.run/updated-at 1})

(deftest generated-worktree-boundary-is-narrow
  (is (maintenance/generated-worktree?
       "/repo/tamaki" "/repo/.tamaki-tamaki-actor-codex-1"))
  (is (not (maintenance/generated-worktree?
            "/repo/tamaki" "/tmp/tamaki-result")))
  (is (not (maintenance/generated-worktree?
            "/repo/tamaki" "/repo/tamaki"))))

(deftest cleanup-preserves-dirty-and-conflicted-results
  (with-redefs [delivery/*process-fn*
                (fn [argv _]
                  (cond
                    (= "status" (nth argv 3))
                    {:exit 0 :out " M src/a.clj\n" :err ""}
                    (= "diff" (nth argv 3))
                    {:exit 0 :out "" :err ""}
                    :else {:exit 0 :out "abc\n" :err ""}))]
    ;; Existence is tested separately in integration; the disposition contract
    ;; is exercised against an existing temp path here.
    (let [source (.toFile
                  (java.nio.file.Files/createTempDirectory
                   "tamaki-maint-source"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
          project (java.io.File. (.getParentFile source)
                                 (str "." (.getName source)
                                      "-tamaki-actor-codex-1"))
          run (assoc terminal-run
                     :agent.run/source-project (.getPath source)
                     :agent.run/project (.getPath project))]
      (.mkdir project)
      (is (= :preserve
             (:maintenance/disposition
              (maintenance/inspect-run run 600002)))))))

(deftest apply-removes-only-explicit-remove-dispositions
  (let [calls (atom [])]
    (binding [delivery/*process-fn*
              (fn [argv cwd]
                (swap! calls conj [argv cwd])
                {:exit 0 :out "" :err ""})]
      (maintenance/apply-plan!
       [{:maintenance/disposition :preserve
         :maintenance/source "/repo/tamaki"
         :maintenance/project "/repo/dirty"}
        {:maintenance/disposition :remove
         :maintenance/source "/repo/tamaki"
         :maintenance/project "/repo/clean"}])
      (is (= [[["git" "-C" "/repo/tamaki" "worktree" "remove"
                "/repo/clean"]
               "/repo/tamaki"]]
             @calls)))))

(deftest independent-repository-with-generated-name-is-preserved
  (let [source (.toFile
                (java.nio.file.Files/createTempDirectory
                 "tamaki-maint-independent"
                 (make-array java.nio.file.attribute.FileAttribute 0)))
        project (java.io.File. (.getParentFile source)
                               (str "." (.getName source)
                                    "-tamaki-actor-grok-1"))
        _ (.mkdirs (java.io.File. project ".git"))
        result (maintenance/inspect-run
                (assoc terminal-run
                       :agent.run/source-project (.getPath source)
                       :agent.run/project (.getPath project))
                600002)]
    (is (= :preserve (:maintenance/disposition result)))
    (is (= :independent-repository (:maintenance/reason result)))))

(deftest generated-output-with-missing-source-is-preserved
  (let [parent (.toFile
                (java.nio.file.Files/createTempDirectory
                 "tamaki-maint-missing-parent"
                 (make-array java.nio.file.attribute.FileAttribute 0)))
        source (io/file parent "gone")
        project (io/file parent ".gone-tamaki-actor-codex-1")]
    (.mkdirs project)
    (let [result (maintenance/inspect-run
                  (assoc terminal-run
                         :agent.run/source-project (.getPath source)
                         :agent.run/project (.getPath project))
                  600002)]
      (is (= :preserve (:maintenance/disposition result)))
      (is (= :source-missing (:maintenance/reason result))))))

(deftest plan-deduplicates-a-worktree-and-keeps-the-safer-disposition
  (with-redefs [maintenance/inspect-run
                (fn [run _]
                  {:maintenance/run (:agent.run/id run)
                   :maintenance/project "/repo/shared"
                   :maintenance/disposition
                   (if (= "dirty" (:agent.run/id run))
                     :preserve :remove)})
                maintenance/inspect-source-conflict (constantly nil)]
    (is (= [{:maintenance/run "dirty"
             :maintenance/project "/repo/shared"
             :maintenance/disposition :preserve}]
           (maintenance/plan [{:agent.run/id "clean"}
                              {:agent.run/id "dirty"}] 1)))))

(defn- temp-dir [prefix]
  (.toFile
   (java.nio.file.Files/createTempDirectory
    prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest inspect-source-conflict-is-nil-when-the-canonical-repo-is-clean
  (with-redefs [delivery/*process-fn* (fn [_ _] {:exit 0 :out "" :err ""})]
    (let [source (temp-dir "tamaki-maint-clean-source")]
      (is (nil? (maintenance/inspect-source-conflict (.getPath source)))))))

(deftest inspect-source-conflict-detects-unmerged-paths-on-the-canonical-repo
  (with-redefs [delivery/*process-fn*
                (fn [_ _] {:exit 0 :out "src/a.clj\nsrc/b.clj\n" :err ""})]
    (let [source (temp-dir "tamaki-maint-conflict-source")
          result (maintenance/inspect-source-conflict (.getPath source))]
      (is (= :conflict (:maintenance/disposition result)))
      (is (= :canonical-unmerged-paths (:maintenance/reason result)))
      (is (= ["src/a.clj" "src/b.clj"] (:maintenance/conflicts result)))
      (is (= (.getPath source) (:maintenance/source result)))
      (is (= (.getPath source) (:maintenance/project result))))))

(deftest inspect-source-conflict-detects-a-stale-index-lock
  (with-redefs [delivery/*process-fn* (fn [_ _] {:exit 0 :out "" :err ""})]
    (let [source (temp-dir "tamaki-maint-lock-source")
          git-dir (io/file source ".git")
          lock (io/file git-dir "index.lock")]
      (.mkdirs git-dir)
      (spit lock "")
      (let [result (maintenance/inspect-source-conflict (.getPath source))]
        (is (= :conflict (:maintenance/disposition result)))
        (is (= :canonical-index-lock (:maintenance/reason result)))
        (is (= [(.getAbsolutePath lock)] (:maintenance/conflicts result)))))))

(deftest inspect-source-conflict-is-nil-without-a-real-source-directory
  (is (nil? (maintenance/inspect-source-conflict "/nonexistent/tamaki-maint-path")))
  (is (nil? (maintenance/inspect-source-conflict nil))))

(deftest plan-surfaces-a-real-unstubbed-canonical-source-conflict
  (with-redefs [maintenance/inspect-run (constantly {:maintenance/disposition :ignored})
                delivery/*process-fn*
                (fn [argv _]
                  (if (= "diff" (nth argv 3))
                    {:exit 0 :out "src/conflicted.clj\n" :err ""}
                    {:exit 0 :out "" :err ""}))]
    (let [source (temp-dir "tamaki-maint-plan-source")
          run {:agent.run/id "run-1" :agent.run/source-project (.getPath source)}
          result (maintenance/plan [run] 1)]
      (is (= 1 (count result)))
      (is (= :conflict (:maintenance/disposition (first result))))
      (is (= :canonical-unmerged-paths (:maintenance/reason (first result)))))))

(deftest integration-frontier-collapses-duplicates-and-prioritizes-code
  (let [plan [{:maintenance/disposition :preserve
               :maintenance/reason :dirty-worktree
               :maintenance/project "/repo/cache"
               :maintenance/paths [" M .cpcache/1.basis"]}
              {:maintenance/disposition :preserve
               :maintenance/reason :dirty-worktree
               :maintenance/project "/repo/code-b"
               :maintenance/paths [" M src/a.clj" "?? test/a_test.clj"]}
              {:maintenance/disposition :preserve
               :maintenance/reason :dirty-worktree
               :maintenance/project "/repo/code-a"
               :maintenance/paths [" M src/a.clj" "?? test/a_test.clj"]}
              {:maintenance/disposition :preserve
               :maintenance/reason :unique-commit
               :maintenance/project "/repo/commit"
               :maintenance/head "abc"}]
        summary (maintenance/summary plan)
        frontier (:maintenance/integration-frontier summary)]
    (is (= 4 (:maintenance/preserved-count summary)))
    (is (= 3 (:maintenance/evidence-groups summary)))
    (is (= 1 (:maintenance/duplicate-evidence summary)))
    (is (= "/repo/commit" (:maintenance/project (first frontier))))
    (is (= "/repo/code-a" (:maintenance/project (second frontier))))
    (is (= 1 (:maintenance/duplicates (second frontier))))
    (is (false? (:maintenance/source-change? (last frontier))))))
