(ns kotoba.tamaki.maintenance-test
  (:require [clojure.test :refer [deftest is testing]]
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
