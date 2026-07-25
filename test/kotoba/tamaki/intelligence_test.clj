(ns kotoba.tamaki.intelligence-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.tamaki.intelligence :as intelligence]))

(deftest blocker-graph-gates-selection
  (let [a (intelligence/issue-node
           {:id "a" :title "foundation" :status :solved})
        b (intelligence/issue-node
           {:id "b" :title "high leverage" :blockers ["a"]
            :signals {:impact 1 :urgency 1}})
        c (intelligence/issue-node
           {:id "c" :title "blocked" :blockers ["missing"]
            :signals {:impact 1 :urgency 1}})
        result (intelligence/selection [a b c])]
    (is (= "b" (get-in result [:issue :issue/id])))
    (is (= 2 (:blocked-count result)))))

(deftest blocker-cycles-are-rejected
  (let [a (intelligence/issue-node {:id "a" :title "a" :blockers ["b"]})
        b (intelligence/issue-node {:id "b" :title "b" :blockers ["a"]})]
    (is (thrown-with-msg? Exception #"contains a cycle"
                          (intelligence/rank [a b])))))

(deftest system-dynamics-affect-priority
  (let [calm (intelligence/issue-node
              {:id "calm" :title "calm" :signals {:impact 0.5}})
        pressure (intelligence/issue-node
                  {:id "pressure" :title "pressure"
                   :signals {:impact 0.5 :feedback-pressure 1}})]
    (is (= "pressure" (get-in (intelligence/selection [calm pressure])
                               [:issue :issue/id])))))

(deftest effect-measures-regression-and-growth
  (is (= {:effect/tests-delta 1 :effect/assertions-delta 4
          :effect/failures-delta 0 :effect/improved? true}
         (intelligence/effect {:tests 10 :assertions 20 :failures 0}
                              {:tests 11 :assertions 24 :failures 0}))))

(deftest issue-metadata-extracts-blockers-and-criteria
  (is (= {:issue/blockers #{"abcdef1"}
          :issue/criteria ["all suites green"]}
         (intelligence/parse-issue-metadata
          "Blocked by: abcdef1\nAcceptance: all suites green\n"))))
