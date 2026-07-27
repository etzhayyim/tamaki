(ns kotoba.tamaki.communication-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.communication :as communication]
            [kotoba.tamaki.intelligence :as intelligence]
            [kotoba.tamaki.topology-projection :as projection]))

(def inbound
  {:org :example :channel :email :external-id "provider-message-42"
   :direction :inbound :occurred-at 1785140000000
   :participant-refs ["private@example.test"]
   :summary "契約判断の確認待ち"
   :blockers ["issue/legal-review"] :consent :not-required})

(deftest communication-becomes-a-blocked-canonical-issue
  (let [node (communication/issue inbound)]
    (is (= :communication (:issue/type node)))
    (is (= #{"issue/legal-review"} (:issue/blockers node)))
    (is (= #{"issue/legal-review"} (:issue/blocked-by node)))
    (is (= :email (:communication/channel node)))
    (is (= :local-private (:issue/visibility node)))
    (is (false? (:issue/projectable? node)))
    (is (true? (:communication/private-content? node)))
    (is (not (str/includes? (pr-str node) "private@example.test")))
    (is (empty? (intelligence/rank [node])))))

(deftest stable-provider-identity-produces-a-stable-issue
  (is (= (:issue/id (communication/issue inbound))
         (:issue/id (communication/issue inbound))))
  (is (not= (:issue/id (communication/issue inbound))
            (:issue/id (communication/issue
                        (assoc inbound :external-id "provider-message-43"))))))

(deftest all-supported-channels-share-the-same-contract
  (is (= #{:email :message :phone}
         (set (map #(-> inbound
                       (assoc :channel %)
                       communication/issue
                       :communication/channel)
                   [:email :message :phone])))))

(deftest pr-history-is-redacted-and-artifact-linked
  (let [node (communication/issue inbound)
        receipt (communication/pr-receipt
                 {:communication-issues [node]
                  :project "orgs/example/service"
                  :issue-id "issue/root"
                  :commit-id "abc" :patch-id "def"
                  :review-verdict :accepted})]
    (is (= :communication/pr-history (:receipt/type receipt)))
    (is (= "def" (:patch/id receipt)))
    (is (true? (:privacy/redacted receipt)))
    (is (not (str/includes? (pr-str receipt) "契約判断")))
    (is (= [(:issue/id node)]
           (mapv :issue/id (:communication/issues receipt))))))

(deftest private-communication-cannot-be-projected-to-radicle
  (let [node (assoc (communication/issue inbound)
                    :issue/projections {:radicle {:id (apply str (repeat 40 "a"))}})
        topology {:topology/id :private-mail
                  :topology/radicle-repo "rad:z-private"
                  :topology/issues [node]}
        observed [{:forge :radicle
                   :forge/id (apply str (repeat 40 "a"))
                   :title "old" :description "old"
                   :status :open :labels #{}}]]
    (is (false? (projection/projectable-issue? node)))
    (is (= [] (projection/radicle-plan topology observed)))))

(deftest invalid-or-underspecified-communications-fail-closed
  (testing "unknown transport"
    (is (thrown? Exception
                 (communication/issue (assoc inbound :channel :social)))))
  (testing "missing provider identity"
    (is (thrown? Exception
                 (communication/issue (dissoc inbound :external-id))))))
