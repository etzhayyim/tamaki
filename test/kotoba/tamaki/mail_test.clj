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

(deftest send-requires-review-of-the-exact-draft
  (let [draft {:org :private-example
               :account :support
               :action :mail/send
               :recipients ["customer@example.test"]
               :subject "確認事項"
               :body "送信前に確認する本文"
               :attachments [{:name "report.pdf"
                              :digest "sha256:example"
                              :size 42
                              :content-type "application/pdf"}]}
        review (mail/review draft)
        receipt (mail/approval-receipt draft :approved :human/operator)
        approved-command (mail/command (assoc draft :approval receipt))
        edited-command (mail/command
                        (assoc draft
                               :body "承認後に変更された本文"
                               :approval receipt))]
    (is (= (:mail/draft-digest (:mail.review/record review))
           (:mail.approval/draft-digest receipt)))
    (is (true? (:mail/executable? approved-command)))
    (is (true? (:mail/approval-bound? approved-command)))
    (is (false? (:mail/review-required? approved-command)))
    (is (false? (:mail/executable? edited-command)))
    (is (false? (:mail/approval-bound? edited-command)))
    (is (true? (:mail/review-required? edited-command)))))

(deftest rejected-or-missing-approval-never-sends
  (let [draft {:org :private-example
               :account :support
               :action :mail/reply
               :recipients ["customer@example.test"]
               :subject "Re: request"
               :body "draft"}
        rejected (mail/approval-receipt draft :rejected :human/operator)]
    (is (false? (:mail/executable? (mail/command draft))))
    (is (false? (:mail/executable?
                 (mail/command (assoc draft :approval rejected)))))))

(deftest missing-org-is-rejected
  ;; The command boundary must fail closed before any transport sees a request
  ;; without a durable org identity.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires org and account"
       (mail/command {:account :support :action :mail/search}))))

(deftest blank-org-is-rejected
  ;; An empty string is truthy in Clojure, so a bare org check alone would
  ;; silently accept it; nameable blank strings must still fail closed.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires org and account"
       (mail/command {:org "" :account :support :action :mail/search})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires org and account"
       (mail/command {:org "   " :account :support :action :mail/search}))))

(deftest missing-or-blank-account-is-rejected
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires org and account"
       (mail/command {:org :private-example :action :mail/search})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires org and account"
       (mail/command {:org :private-example :account ""
                      :action :mail/search})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires org and account"
       (mail/command {:org :private-example :account "   "
                      :action :mail/search}))))

(deftest non-nameable-org-is-rejected-with-a-clear-message
  ;; A numeric :org passes a bare truthy check but used to crash inside
  ;; `(name org)` with an opaque ClassCastException. The type guard turns
  ;; that into the same actionable validation error as a missing org.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires org and account"
       (mail/command {:org 123 :account :support :action :mail/search})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires org and account"
       (mail/command {:org {:id :private} :account :support
                      :action :mail/search}))))
