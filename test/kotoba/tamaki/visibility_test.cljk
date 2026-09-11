(ns kotoba.tamaki.visibility-test
  (:require [clojure.test :refer [deftest is testing]]
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
  (is (= :public-allowed (:github/mirror
                          (visibility/policy :public-example
                                             {:public-organisms
                                              #{:public-example}}))))
  (is (= :blocked (:issue/authority
                   (visibility/policy :unknown)))))

(deftest private-radicle-publication-is-rejected
  (testing "private visibility with a Radicle capability is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Private actor cannot use Radicle"
         (visibility/validate-actor
          {:actor/id :private
           :actor/organism :private-example
           :actor/capabilities #{:radicle}}))))
  (testing "private visibility with Radicle issue authority is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Private actor cannot use Radicle"
         (visibility/validate-actor
          {:actor/id :private
           :actor/organism :private-example
           :actor/repository-visibility :private
           :actor/issue-authority :radicle})))))

(deftest federated-actors-receive-policy-aligned-mirror-defaults
  ;; Production (`actor/validate-spec`) always threads every federated ActorSpec
  ;; through `validate-actor`. The happy path that annotates visibility fields
  ;; and the public-mirror branch previously had no direct coverage, so a
  ;; regression could mark public Radicle actors as mirror-blocked or drop
  ;; annotations entirely without a red test.
  (testing "legacy non-federated actors pass through unchanged"
    (is (= {:actor/id :legacy} (visibility/validate-actor {:actor/id :legacy}))))
  (testing "organism alone fails closed to private/blocked/blocked"
    (is (= {:actor/id :unknown
            :actor/organism :unknown
            :actor/repository-visibility :private
            :actor/issue-authority :blocked
            :actor/github-mirror :blocked}
           (visibility/validate-actor
            {:actor/id :unknown :actor/organism :unknown}))))
  (testing "private GitHub-primary actors keep GitHub as the primary surface"
    (is (= {:actor/id :private
            :actor/organism :private-example
            :actor/repository-visibility :private
            :actor/issue-authority :github-private
            :actor/github-mirror :primary}
           (visibility/validate-actor
            {:actor/id :private
             :actor/organism :private-example
             :actor/repository-visibility :private
             :actor/issue-authority :github-private}))))
  (testing "public Radicle actors receive the public-allowed mirror mode"
    (is (= {:actor/id :public
            :actor/organism :public-example
            :actor/repository-visibility :public
            :actor/issue-authority :radicle
            :actor/github-mirror :public-allowed
            :actor/capabilities #{:implementation}}
           (visibility/validate-actor
            {:actor/id :public
             :actor/organism :public-example
             :actor/repository-visibility :public
             :actor/issue-authority :radicle
             :actor/capabilities #{:implementation}})))))
