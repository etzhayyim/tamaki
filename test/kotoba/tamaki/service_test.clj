(ns kotoba.tamaki.service-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.service :as service]))

(def spec
  {:service/id :example/support
   :service/domain :example.test
   :service/org :example
   :service/project "/repo"
   :service/topology-file "/tmp/example-service.edn"
   :service/policy service/default-policy})

(deftest unknown-observations-create-one-runnable-measurement-root
  (let [topology (service/topology
                  spec {:business/status :unobserved
                        :business/kpis {}} 1)
        walk (service/active-walk topology)]
    (is (= "service/example.test/observe"
           (:issue/id (first (filter :issue/runnable? walk)))))
    (is (every? #(= :local-private (:issue/visibility %))
                (:topology/issues topology)))
    (is (every? false? (map :issue/projectable?
                            (:topology/issues topology))))))

(deftest support-and-reliability-gaps-produce-dependent-work
  (let [summary {:business/status :observed
                 :business/observation
                 {:stocks {:untriaged-inbox 4 :support-backlog 8
                           :awaiting-human 5 :open-incidents 1}}
                 :business/kpis
                 {:unique-visitors 200 :activation-rate 0.5
                  :paid-conversion-rate 0.2 :churn-rate 0.01}}
        topology (service/topology spec summary 2)
        issues (into {} (map (juxt :issue/lane identity)
                             (:topology/issues topology)))]
    (is (= :open (get-in issues [:incident-response :issue/status])))
    (is (= ["service/example.test/incident-response"]
           (get-in issues [:support-triage :issue/blocked-by])))
    (is (= ["service/example.test/support-triage"]
           (get-in issues [:support-draft :issue/blocked-by])))
    (is (= :open (get-in issues [:human-decision :issue/status])))
    (is (= :closed (get-in issues [:conversion :issue/status])))
    (is (= :closed (get-in issues [:outcome-validation :issue/status])))))

(deftest topology-write-is-readable-and-stable
  (let [dir (.toFile
             (java.nio.file.Files/createTempDirectory
              "tamaki-service-" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (java.io.File. dir "topology.edn")
        topology (service/topology
                  spec {:business/status :unobserved :business/kpis {}} 3)]
    (service/write-topology! (.getPath file) topology)
    (is (= topology (edn/read-string (slurp file))))))
