(ns kotoba.tamaki.topology-projection-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.tamaki.topology-projection :as projection]))

(def topology
  {:topology/id :demo
   :topology/radicle-repo "rad:z123"
   :topology/issues
   [{:issue/id "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
     :issue/title "Root"
     :issue/description "Canonical body"
     :issue/status :open
     :issue/layer :foundation
     :issue/priority :p0
     :issue/blocked-by []}
    {:issue/id "child"
     :issue/title "Child"
     :issue/status :open
     :issue/layer :product
     :issue/priority :p1
     :issue/blocked-by ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
     :issue/projections
     {:radicle {:id "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}}]})

(deftest import-preserves-canonical-dependencies
  (let [observed [{:forge :radicle
                   :forge/id "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                   :title "Root renamed"
                   :description "Imported body"
                   :status :open
                   :labels #{"priority:p0"}}]
        merged (:topology (projection/import-plan topology observed []))
        issue (first (:topology/issues merged))]
    (is (= [] (:issue/blocked-by issue)))
    (is (= "Root renamed" (:issue/title issue)))
    (is (= "Imported body" (:issue/description issue)))
    (is (= "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
           (get-in issue [:issue/projections :radicle :id])))))

(deftest repeated-import-is-idempotent
  (let [observed [{:forge :radicle
                   :forge/id "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                   :title "Root" :description "Canonical body"
                   :status :open
                   :labels #{"layer:foundation"
                             "priority:p0" "program:demo"}}]
        once (:topology (projection/import-plan topology observed []))
        twice (projection/import-plan once observed [])]
    (is (zero? (:import/created twice)))
    (is (zero? (:import/updated twice)))
    (is (= once (:topology twice)))))

(deftest github-import-matches-title-instead-of-duplicating
  (let [observed [{:forge :github :forge/id 7 :repo "o/r"
                   :title "Root" :description "Mirror" :status :open
                   :labels #{} :url "https://example/7"}]
        plan (projection/import-plan topology [] observed)
        root (first (get-in plan [:topology :topology/issues]))]
    (is (= 2 (count (get-in plan [:topology :topology/issues]))))
    (is (= 7 (get-in root [:issue/projections :github :id])))
    (is (= [] (:issue/blocked-by root)))))

(deftest projector-preserves-human-labels
  (let [observed [{:forge :radicle
                   :forge/id "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                   :title "Old" :description "Old body" :status :closed
                   :labels #{"program:old" "priority:p2" "security"}}]
        plan (projection/radicle-plan
              (update topology :topology/issues #(vector (first %)))
              observed)]
    (is (= [:edit :label-add :label-delete :state]
           (mapv :action plan)))
    (is (= ["layer:foundation" "priority:p0" "program:demo"]
           (:labels (second plan))))
    (is (= ["priority:p2" "program:old"] (:labels (nth plan 2))))
    (is (= :open (:status (last plan))))))

(deftest missing-radicle-object-fails-closed
  (is (= [{:action :missing
           :issue/id "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]
         (projection/radicle-plan
          (update topology :topology/issues #(vector (first %))) []))))
