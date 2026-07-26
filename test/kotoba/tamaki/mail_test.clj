(ns kotoba.tamaki.mail-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.mail :as mail]))

(deftest governed-mail-decisions
  (testing "reading and drafting do not create an external side effect"
    (is (= :autonomous (mail/decision {:action :mail/sync})))
    (is (= :autonomous (mail/decision {:action :mail/draft}))))
  (testing "delivery always crosses a human authority boundary"
    (is (= :approval-required
           (mail/decision {:action :mail/send
                           :recipients ["customer@example.test"]})))
    (is (= :blocked (mail/decision {:action :mail/send})))
    (is (= :blocked
           (mail/decision {:action :mail/send
                           :recipients ["many@example.test"]
                           :bulk? true})))))

(deftest command-carries-no-secret
  (let [command (mail/command {:org :private-example
                               :account :support
                               :action :mail/search
                               :query "is:unread"})]
    (is (= :autonomous (:mail/decision command)))
    (is (false? (:mail/credential-material? command)))))
