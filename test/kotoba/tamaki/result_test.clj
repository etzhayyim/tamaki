(ns kotoba.tamaki.result-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.tamaki.result :as result]))

(deftest typed-events-project-to-source-pr-result-graph
  (let [run {:agent.run/id "run-1" :agent.run/project "/repo"}
        patch {:tamaki.event/run "run-1" :tamaki.event/kind :patch/created
               :tamaki.event/data {:issue/id "i1" :commit/id "c1"
                                   :patch/id "p1"}}
        review {:tamaki.event/run "run-1"
                :tamaki.event/kind :review/independent
                :tamaki.event/data {:patch/id "p1"
                                    :review/verdict :accepted}}
        [graph] (result/result-graphs
                 [patch review] [run]
                 {"candidate" {:evolution/patch-id "p1"
                               :evolution/pr-url
                               "https://github.test/pr/1"}})]
    (is (= [:issue :source :radicle :github :review]
           (mapv :result.node/type (:result/nodes graph))))
    (is (= 4 (count (:result/edges graph))))
    (is (= "/repo" (:result/project graph)))
    (is (= #{{:result.edge/from "issue/i1"
              :result.edge/to "source/c1"
              :result.edge/type :result/produced}
             {:result.edge/from "source/c1"
              :result.edge/to "radicle/p1"
              :result.edge/type :result/produced}
             {:result.edge/from "radicle/p1"
              :result.edge/to "github/https://github.test/pr/1"
              :result.edge/type :result/produced}
             {:result.edge/from "radicle/p1"
              :result.edge/to "review/p1"
              :result.edge/type :result/produced}}
           (set (:result/edges graph))))
    (is (not-any? #(= "github/https://github.test/pr/1"
                      (:result.edge/from %))
                  (:result/edges graph))
        "review must hang off the Radicle patch, not the GitHub mirror")))

(deftest full-lifecycle-includes-merge-and-prefers-source-project
  (let [run {:agent.run/id "run-2"
             :agent.run/project "/worktree"
             :agent.run/source-project "/canonical"}
        patch {:tamaki.event/run "run-2" :tamaki.event/kind :patch/created
               :tamaki.event/data {:issue/id "i2" :commit/id "c2"
                                   :patch/id "p2"}}
        review {:tamaki.event/run "run-2"
                :tamaki.event/kind :review/independent
                :tamaki.event/data {:patch/id "p2"
                                    :review/verdict :accepted}}
        integrated {:tamaki.event/run "run-2"
                    :tamaki.event/kind :patch/integrated
                    :tamaki.event/data {:patch/id "p2"}}
        [graph] (result/result-graphs
                 [patch review integrated] [run]
                 {"candidate" {:evolution/patch-id "p2"
                               :evolution/pr-url
                               "https://github.test/pr/2"}})]
    (is (= "/canonical" (:result/project graph)))
    (is (= [:issue :source :radicle :github :review :merge]
           (mapv :result.node/type (:result/nodes graph))))
    (is (= 5 (count (:result/edges graph))))
    (is (some #(and (= "radicle/p2" (:result.edge/from %))
                    (= "merge/p2" (:result.edge/to %)))
              (:result/edges graph)))))

(deftest radicle-only-delivery-omits-github-mirror
  (let [run {:agent.run/id "run-3" :agent.run/project "/repo"}
        patch {:tamaki.event/run "run-3" :tamaki.event/kind :patch/created
               :tamaki.event/data {:issue/id "i3" :commit/id "c3"
                                   :patch/id "p3"}}
        review {:tamaki.event/run "run-3"
                :tamaki.event/kind :review/independent
                :tamaki.event/data {:patch/id "p3"
                                    :review/verdict :accepted}}
        [graph] (result/result-graphs [patch review] [run] {})]
    (is (= [:issue :source :radicle :review]
           (mapv :result.node/type (:result/nodes graph))))
    (is (= 3 (count (:result/edges graph))))
    (is (= #{{:result.edge/from "issue/i3"
              :result.edge/to "source/c3"
              :result.edge/type :result/produced}
             {:result.edge/from "source/c3"
              :result.edge/to "radicle/p3"
              :result.edge/type :result/produced}
             {:result.edge/from "radicle/p3"
              :result.edge/to "review/p3"
              :result.edge/type :result/produced}}
           (set (:result/edges graph))))))
