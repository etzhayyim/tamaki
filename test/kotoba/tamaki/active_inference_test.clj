(ns kotoba.tamaki.active-inference-test
  (:require [clojure.test :refer [deftest is]]
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

(deftest relational-agency-and-consent-are-hard-policy-gates
  (let [belief (inference/belief-state {:impact 0.8 :risk 0.1})
        selected (inference/select-policy
                  belief
                  [{:id "replicate-without-consent"
                    :requires-consent? true :consent? false
                    :observations {:impact 1.0 :risk 0.0}}
                   {:id "repair-relationship"
                    :wellbecoming
                    {:human-agency 0.9 :relational-trust 0.7
                     :inheritable-learning 0.7 :future-optionality 0.8
                     :succession-integrity 0.9}
                    :observations {:impact 0.6 :risk 0.1}}])]
    (is (= "repair-relationship" (:policy/id selected)))
    (is (= :allowed (:policy/gate selected)))))
