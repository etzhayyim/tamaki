(ns kotoba.tamaki.evolution-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.evolution :as evolution]))

(def proposed
  (evolution/candidate
   {:issue "93971f39ceb295136d4769bd4ce3a7a94ddeb030"
    :objective "safe self evolution" :project "/repo"
    :base-commit "abc123" :branch "evolution/rad-93971f3"
    :worktree "/tmp/evolution-4"}
   1))

(deftest lifecycle-is-ordered-and-fail-closed
  (is (= :radicle (:evolution/authority proposed)))
  (is (evolution/radicle-id? (:evolution/issue proposed)))
  (is (false? (evolution/radicle-id? "4")))
  (is (= :implemented
         (:evolution/status
          (evolution/transition proposed :implemented 2 {}))))
  (is (thrown? Exception
               (evolution/transition proposed :promoted 2 {}))))

(deftest promotion-requires-radicle-patch-fitness-replay-canary-and-human-boundary
  (let [ready (assoc proposed
                     :evolution/status :awaiting-human
                     :evolution/patch-id "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                     :evolution/pr-url "https://github.com/kotoba-lang/tamaki/pull/5"
                     :evolution/tests-passed? true
                     :evolution/review-accepted? true
                     :evolution/replay-passed? true
                     :evolution/canary-passed? true
                     :evolution/fitness-before {:tests 60 :assertions 180
                                                :failures 1}
                     :evolution/fitness-after {:tests 70 :assertions 210
                                               :failures 0})]
    (is (evolution/promotion-ready? ready))
    (testing "any missing gate denies promotion"
      (doseq [gate [:evolution/tests-passed?
                    :evolution/review-accepted?
                    :evolution/replay-passed?
                    :evolution/canary-passed?]]
        (is (false? (evolution/promotion-ready? (assoc ready gate false))))))
    (is (false? (evolution/promotion-ready?
                 (assoc ready :evolution/patch-id nil))))
    (testing "GitHub mirror is optional"
      (is (evolution/promotion-ready? (assoc ready :evolution/pr-url nil))))))

(deftest durable-events-rebuild-candidates
  (let [events [(evolution/event proposed :evolution/proposed 1
                                 {:candidate proposed})
                (evolution/event proposed :evolution/transition 2
                                 {:status :implemented
                                  :evidence {:evolution/commit "def456"}})]
        folded (get (evolution/candidates events) (:evolution/id proposed))]
    (is (= :implemented (:evolution/status folded)))
    (is (= "def456" (:evolution/commit folded)))))
