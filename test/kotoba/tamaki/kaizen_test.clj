(ns kotoba.tamaki.kaizen-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.tamaki.kaizen :as kaizen]))

(defn event [kind at]
  {:tamaki.event/kind kind :tamaki.event/at at})

(deftest detects-review-integration-bottleneck
  (let [result (kaizen/evaluate
                [(event :run/started 900)
                 (event :patch/created 910)
                 (event :review/independent 920)]
                [] 1000 200)]
    (is (= :heal-review-integration-bottleneck
           (:kaizen/decision result)))
    (is (= 0.0 (get-in result
                       [:kaizen/evidence :review->integrate])))
    (is (:kaizen/requires-approval result))))

(deftest recommends-continuation-only-after-integration
  (let [result (kaizen/evaluate
                [(event :run/started 900)
                 (event :patch/created 910)
                 (event :review/independent 920)
                 (event :patch/integrated 930)]
                [] 1000 200)]
    (is (= :continue (:kaizen/decision result)))
    (is (= 1.0 (get-in result
                       [:kaizen/evidence :review->integrate])))))
