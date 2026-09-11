(ns kotoba.tamaki.topology-projection-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.tamaki.delivery :as delivery]
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

(deftest apply-plan-executes-commands-and-records-success
  ;; apply-plan! backs `tamaki topology project --execute`; it is the only
  ;; function that actually mutates the Radicle issue tracker, yet it had no
  ;; direct test coverage before this change.
  (with-redefs [delivery/execute!
                (fn [command _project]
                  (is (= ["rad" "issue" "label"] (vec (take 3 command))))
                  {:exit 0 :out "ok" :err ""})]
    (let [plan [{:action :label-add :issue/id "a"
                 :command ["rad" "issue" "label" "--add" "priority:p0"]}]
          operation (first (projection/apply-plan! plan "/project"))]
      (is (true? (:ok? operation)))
      (is (= {:exit 0 :out "ok" :err ""} (:result operation))))))

(deftest apply-plan-records-failure-for-a-nonzero-exit
  (with-redefs [delivery/execute!
                (fn [_command _project] {:exit 1 :out "" :err "denied"})]
    (let [plan [{:action :state :issue/id "a" :command ["rad" "issue" "state"]}]
          operation (first (projection/apply-plan! plan "/project"))]
      (is (false? (:ok? operation)))
      (is (= "denied" (:err (:result operation)))))))

(deftest apply-plan-marks-a-missing-command-as-projection-missing
  ;; `:missing` plan entries (see radicle-plan) never carry a :command;
  ;; apply-plan! must not attempt to shell out for them.
  (let [called? (atom false)]
    (with-redefs [delivery/execute! (fn [& _] (reset! called? true) {:exit 0})]
      (let [operation (first (projection/apply-plan!
                               [{:action :missing :issue/id "a"}] "/project"))]
        (is (false? @called?))
        (is (false? (:ok? operation)))
        (is (= :projection-missing (:error operation)))))))

(deftest topology-round-trips-through-write-and-read
  (let [file (java.io.File/createTempFile "tamaki-topology" ".edn")]
    (try
      (projection/write-topology! (.getAbsolutePath file) topology)
      (is (= topology (projection/read-topology (.getAbsolutePath file))))
      (finally (.delete file)))))
