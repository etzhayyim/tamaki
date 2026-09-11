(ns kotoba.tamaki.bridge-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.tamaki.bridge :as bridge]))

(deftest bridge-plan-keeps-radicle-authoritative
  (let [candidate {:evolution/id "candidate-1"
                   :evolution/status :tested
                   :evolution/issue "i"
                   :evolution/patch-id "p"}
        [gap] (bridge/plan {"candidate-1" candidate})]
    (is (= :open-draft-pr (:bridge/action gap)))
    (is (= :radicle (:bridge/authority gap))))
  (is (= :observe-github
         (:bridge/action
          (bridge/candidate-gap
           {:evolution/id "candidate-2"
            :evolution/status :reviewed
            :evolution/pr-url "https://github.example/pr/1"}))))
  (is (nil? (bridge/candidate-gap
             {:evolution/status :promoted
              :evolution/patch-id "p"}))))

(deftest github-observation-is-bounded
  (let [observation (bridge/github-observation
                     {:exit 0 :out (apply str (repeat 3000 "x"))} 10)]
    (is (= 10 (:evolution/github-observed-at observation)))
    (is (= 2000
           (count (get-in observation
                          [:evolution/github-observation :summary]))))))
