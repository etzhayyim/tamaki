(ns kotoba.tamaki.adapters-test
  (:require [clojure.test :refer [deftest is]]
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
