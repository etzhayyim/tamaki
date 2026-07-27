(ns kotoba.tamaki.result-evaluation-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.result-evaluation :as evaluation]))

(def valid-evaluation
  {:evaluation/id :evaluation/p1
   :evaluation/result "result/p1"
   :evaluation/rubric-version 1
   :evaluation/scores
   {:correctness 1.0 :verification 1.0 :integration 0.9
    :measured-impact 0.4 :durability 0.8 :efficiency 0.6
    :novelty 0.3 :safety 1.0 :learning-value 0.7}
   :evaluation/gates
   {:tests-green? true :independent-review? true
    :secret-scan-clean? true :authority-correct? true
    :human-consent? true}
   :evaluation/evidence
   [{:evidence/type :test :evidence/ref "test-report.edn"}]
   :evaluation/confidence 0.8})

(deftest evidence-gated-score-and-validation-schedule
  (let [result (evaluation/evaluate valid-evaluation 1000)]
    (is (= :awaiting-production-validation (:evaluation/status result)))
    (is (< 0.0 (:evaluation/score result) 1.0))
    (is (= (+ 1000 (* 7 24 60 60 1000))
           (get-in result [:evaluation/validation-due :seven-day]))))
  (testing "one failed hard gate makes the scalar score zero"
    (is (zero? (evaluation/score
                (assoc-in valid-evaluation
                          [:evaluation/gates :secret-scan-clean?] false))))))

(deftest malformed-or-unsupported-claims-fail-closed
  (is (thrown? Exception
               (evaluation/score
                (update valid-evaluation :evaluation/scores
                        dissoc :correctness))))
  (is (thrown? Exception
               (evaluation/score
                (assoc valid-evaluation :evaluation/id nil))))
  (is (thrown? Exception
               (evaluation/score
                (assoc valid-evaluation :evaluation/risk-penalty -0.1))))
  (is (thrown? Exception
               (evaluation/validation
                {:evaluation-id :e :result "result/p1"
                 :window :seven-day :observed-score 0.8 :evidence []}
                1000)))
  (is (thrown? Exception
               (evaluation/validation
                {:evaluation-id nil :result nil
                 :window :seven-day :observed-score 0.8
                 :evidence [{:evidence/type :metric
                             :evidence/ref "observation.edn"}]}
                1000))))

(deftest evidence-bearing-pairwise-tournament-ranks-winner
  (let [result
        (evaluation/tournament
         {:tournament/id :t1
          :tournament/issue :issue/i1
          :tournament/rubric-version 1
          :tournament/candidates ["result/a" "result/b"]
          :tournament/matches
          [{:match/left "result/a" :match/right "result/b"
            :match/winner "result/b"
            :match/evidence [{:evidence/type :test
                              :evidence/ref "comparison.edn"}]}]})]
    (is (= ["result/b" "result/a"] (:tournament/ranking result)))
    (is (> (get-in result [:tournament/elo "result/b"]) 1500.0))))
