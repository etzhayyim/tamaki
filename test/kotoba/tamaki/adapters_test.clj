(ns kotoba.tamaki.adapters-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.adapters :as adapters]
            [kotoba.tamaki.model :as model]))

(deftest fleet-work-contract
  (let [run (model/agent-run {:goal "test"
                              :mode :fleet
                              :repo "kotoba-lang/demo"
                              :pin "abc123"
                              :node :auto
                              :capabilities #{:git :nbb}}
                             1)
        work (adapters/fleet-work run)]
    (is (= "kotoba-lang/demo" (:repo work)))
    (is (= "auto" (:node work)))
    (is (= #{:git :nbb} (:requires work)))
    (is (= "test" (:prompt work)))))

(deftest operator-command-routing
  (let [infer (adapters/murakumo-command :infer ["plan" "model-a"])
        nodes (adapters/fleet-tool-command "nodes" ["--nodes" "a,b"])]
    (is (= "murakumo.infer" (nth infer 4)))
    (is (= ["plan" "model-a"] (subvec (vec infer) 5)))
    (is (re-find #"fleet-nodes\.cljs$" (nth nodes 3)))
    (is (= ["--nodes" "a,b"] (subvec (vec nodes) 4)))))

(deftest local-resume-routes-through-kotoba-code
  (let [run (model/agent-run {:goal "continue"
                              :project "/tmp/project"
                              :model "codex:"}
                             1)
        command (adapters/local-resume-command run)]
    (is (re-find #"kotoba-code$" (first command)))
    (is (= ["--resume" "/tmp/project" "codex:"]
           (subvec (vec command) 1)))))

(deftest command-existence-treats-input-as-data
  (is (true? (adapters/command-exists? "sh")))
  (is (false? (adapters/command-exists?
               "tamaki-command-that-does-not-exist; true"))))

(deftest process-environment-is-injectable
  (let [observed (atom nil)]
    (binding [adapters/*process-env* {"KC_LOOP_ID" "run-1"}
              adapters/*execute-fn*
              (fn [_ _]
                (reset! observed adapters/*process-env*)
                0)]
      (is (zero? (adapters/execute! ["true"])))
      (is (= {"KC_LOOP_ID" "run-1"} @observed)))))

(deftest runner-can-remove-inherited-authentication
  ;; Assert through the child exit code. Never print the inherited environment:
  ;; a test log is still an exfiltration surface for unrelated credentials.
  (binding [adapters/*process-env*
            {"ANTHROPIC_API_KEY" "must-not-reach-runner"
             "TAMAKI_VISIBLE_TEST" "yes"}
            adapters/*unset-process-env* ["ANTHROPIC_API_KEY"]]
    (is
     (zero?
      (adapters/execute!
       ["/bin/sh" "-c"
        "test -z \"${ANTHROPIC_API_KEY:-}\" && test \"$TAMAKI_VISIBLE_TEST\" = yes"])))))

(deftest shared-contract-namespaces-must-be-on-the-classpath
  ;; Regression for the post-capability-extract failure mode: bb launched with
  ;; only `src:../hil/src` could not require kotoba.core.actor-capability, so
  ;; every bin/tamaki and bb test invocation died before dispatch.
  (is (true? (adapters/shared-contract-ready?)))
  (doseq [ns-sym adapters/required-shared-namespaces]
    (is (some? (find-ns ns-sym)) (str ns-sym)))
  (let [report (adapters/readiness)]
    (is (true? (get-in report [:capability-contract :ok?])))
    (is (= adapters/required-shared-namespaces
           (get-in report [:capability-contract :namespaces])))
    (is (true? (get-in report [:clojure :ok?]))
        "bin/tamaki resolves deps.edn via the clojure CLI")))

(deftest bin-tamaki-resolves-deps-edn-classpath
  ;; Guard the operator entrypoint: sibling-only babashka classpaths are the
  ;; exact regression that took down supervisors after the capability extract.
  (let [script (slurp "bin/tamaki")
        bb-edn (slurp "bb.edn")]
    (testing "bin/tamaki uses deps.edn, not a sibling-only classpath"
      (is (str/includes? script "clojure -Spath"))
      (is (not (str/includes? script "$ROOT/src:$ROOT/../hil/src"))))
    (testing "bb tasks share the fixed entrypoint and JVM suite"
      (is (str/includes? bb-edn "bin/tamaki"))
      (is (str/includes? bb-edn "\"clojure\" \"-M:test\"")))))
