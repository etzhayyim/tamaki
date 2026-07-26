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
    (is (= "/repo" (:result/project graph)))))
