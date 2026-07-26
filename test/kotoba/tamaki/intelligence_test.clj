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
          :issue/criteria ["all suites green across both runtimes"]
          :issue/managed? true}
         (intelligence/parse-issue-metadata
          (str "╭──────────────────────────────╮\n"
               "│ Managed by: tamaki-supervisor │\n"
               "│                               │\n"
               "│ Blocked by: abcdef1           │\n"
               "│ Acceptance: all suites green  │\n"
               "│ across both runtimes          │\n"
               "╰──────────────────────────────╯")))))

(deftest unmanaged-issue-is-not-claimed-by-the-supervisor
  (is (= {:issue/blockers #{}
          :issue/criteria ["owner decides -- keep as-is"]
          :issue/managed? false}
         (intelligence/parse-issue-metadata
          (str "│ Acceptance: owner decides -- │\n"
               "│ keep as-is                    │")))))

(deftest issue-list-parses-open-issues-and-ignores-chrome
  (let [output (str "╭───────────────────────────────────────────────────╮\n"
                     "│ ●   ID        Title                    Author  Opened        │\n"
                     "├─────────────────────────────────────────────────┤\n"
                     "│ ●   abc1234f  Add bounded retries       alice   3 days ago   │\n"
                     "│ ●   def56789  Improve coverage          bob     1 week ago   │\n"
                     "╰──────────────────────────────────────────────╯\n")]
    (is (= [(intelligence/issue-node {:id "abc1234f" :title "Add bounded retries"})
            (intelligence/issue-node {:id "def56789" :title "Improve coverage"})]
           (intelligence/parse-issue-list output)))))

(deftest issue-list-of-blank-output-is-empty
  (is (= [] (intelligence/parse-issue-list "")))
  (is (= [] (intelligence/parse-issue-list nil))))

(deftest independent-verdict-is-fail-closed
  (is (intelligence/valid-review-verdict?
       {:review/verdict :accepted :review/commit "abc"
        :review/evidence ["tests green"]}
       "abc"))
  (is (not (intelligence/valid-review-verdict?
            {:review/verdict :accepted :review/commit "wrong"
             :review/evidence ["tests green"]}
            "abc")))
  (is (not (intelligence/valid-review-verdict?
            {:review/verdict :rejected :review/commit "abc"
             :review/evidence ["criterion failed"]}
            "abc")))
  (is (not (intelligence/valid-review-verdict? nil "abc"))))
