(ns kotoba.tamaki.active-inference-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.active-inference :as inference]))

(deftest beliefs-update-with-bounded-uncertainty
  (let [belief (inference/belief-state
                {:impact 0.9 :urgency 0.8 :risk 0.1})]
    (is (every? #(<= 0.0 % 1.0)
                (vals (:belief/means belief))))
    (is (pos? (inference/prediction-error
               belief {:impact 0.1 :urgency 0.1 :risk 0.9})))))

(deftest expected-free-energy-prefers-useful-low-risk-policy
  (let [belief (inference/belief-state
                {:impact 0.6 :urgency 0.6 :confidence 0.7
                 :risk 0.2 :effort 0.4})
        selected
        (inference/select-policy
         belief
         [{:id "safe" :observations
           {:impact 0.9 :urgency 0.8 :confidence 0.9 :risk 0.1 :effort 0.2}}
          {:id "risky" :observations
           {:impact 0.9 :urgency 0.8 :confidence 0.4 :risk 1.0 :effort 0.9}}])]
    (is (= "safe" (:policy/id selected)))
    (is (number? (:policy/expected-free-energy selected)))))

(deftest belief-state-prior-persists-across-cycles-without-resetting-to-default
  ;; Production (`cli.clj`, the tamaki-loop cycle) always threads the
  ;; previous cycle's `:belief/means` back in as the prior to the next
  ;; `belief-state` call, but every existing test above called only the
  ;; 1-arity (implicit nil-prior) entry point, so the 2-arity carry-forward
  ;; path had zero direct coverage.
  (let [initial (inference/belief-state {:impact 1.0 :risk 0.1})
        established-risk (get-in initial [:belief/means :risk])
        next-cycle (inference/belief-state initial {:impact 1.0})]
    (testing "an established off-default belief is not reset when a dimension goes unobserved"
      (is (< established-risk 0.5))
      (is (= established-risk (get-in next-cycle [:belief/means :risk]))))
    (testing "a repeatedly observed dimension keeps moving toward the observation without jumping there"
      (is (< (get-in initial [:belief/means :impact])
             (get-in next-cycle [:belief/means :impact])
             1.0)))))

(deftest belief-state-observations-are-scoped-to-known-dimensions
  ;; `:belief/observations` is built with `select-keys` against the fixed
  ;; `dimensions` vector. Production merges business KPI maps (mrr-jpy,
  ;; churn-rate, etc.) into the observation map before calling `belief-state`,
  ;; so an untracked key must not leak into the durable belief record.
  (let [belief (inference/belief-state {:impact 0.6 :mrr-jpy 800000})]
    (is (= #{:impact} (set (keys (:belief/observations belief)))))))
