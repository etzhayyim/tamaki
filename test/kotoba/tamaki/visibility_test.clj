(ns kotoba.tamaki.visibility-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.tamaki.visibility :as visibility]))

(deftest organism-visibility-policy
  (is (= :radicle (:issue/authority
                   (visibility/policy :public-example
                                      {:public-organisms #{:public-example}}))))
  (is (= :github-private (:issue/authority
                          (visibility/policy :private-example
                                             {:private-organisms
                                              #{:private-example}}))))
  (is (= :primary (:github/mirror
                   (visibility/policy :private-example
                                      {:private-organisms
                                       #{:private-example}}))))
  (is (= :blocked (:issue/authority
                   (visibility/policy :unknown)))))

(deftest private-radicle-publication-is-rejected
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Private actor cannot use Radicle"
       (visibility/validate-actor
        {:actor/id :private
         :actor/organism :private-example
         :actor/capabilities #{:radicle}}))))
