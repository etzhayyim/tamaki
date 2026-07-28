(ns kotoba.tamaki.replication-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.replication :as replication]))

(def config
  {:replication/organism :tamaki
   :replication/recipient "age1example"
   :replication/min-interval-ms 1000
   :replication/targets
   [{:target/id :a :target/transport :ssh :target/host "a"
     :target/path "~/.local/share/tamaki" :target/failure-domain :rack-a}
    {:target/id :b :target/transport :ssh :target/host "b"
     :target/path "/var/lib/tamaki" :target/failure-domain :rack-b}]})

(deftest validates-independent-ssh-targets
  (is (= config (replication/validate-config config)))
  (testing "shell metacharacters cannot enter an SSH command"
    (is (thrown? Exception
                 (replication/validate-config
                  (assoc-in config
                            [:replication/targets 0 :target/host]
                            "a;shutdown"))))))

(deftest minimum-interval-bounds-replication
  (is (replication/due? config nil 1000))
  (testing "first snapshot is always due even under a synthetic early clock"
    ;; Without a prior receipt the min-interval must not postpone establishing
    ;; the initial disaster-recovery baseline. Previously `(due? … nil 0)` was
    ;; false when now-ms < min-interval, and reconcile! then NPEd on nil.
    (is (replication/due? config nil 0))
    (is (replication/due? config nil 999)))
  (is (not (replication/due?
            config {:replication/at 900} 1000)))
  (is (replication/due?
       config {:replication/at 900} 1900)))

(deftest not-due-restates-the-prior-receipt-schedule
  ;; When a prior receipt exists and the min-interval has not elapsed,
  ;; reconcile! must restate that receipt with :not-due and a concrete
  ;; :replication/next-at — never invent a nil-based schedule.
  (let [root (io/file (System/getProperty "java.io.tmpdir")
                      (str "tamaki-replication-" (random-uuid)))
        receipts (io/file root "replication" "receipts")
        prior {:replication/version 1
               :replication/status :healthy
               :replication/at 900
               :replication/durable-replicas 3}]
    (try
      (.mkdirs receipts)
      (spit (io/file receipts "900.edn") (pr-str prior))
      (let [result (replication/reconcile! (.getPath root) config 1000)]
        (is (= :not-due (:replication/status result)))
        (is (= 1900 (:replication/next-at result)))
        (is (= 3 (:replication/durable-replicas result)))
        (is (= 900 (:replication/at result))))
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))

(deftest observation-update-preserves-comments
  (let [file (io/file (System/getProperty "java.io.tmpdir")
                      (str "tamaki-observation-" (random-uuid) ".edn"))]
    (try
      (spit file "{;; evidence\n :durable-replicas 1}\n")
      (replication/update-observation! (.getPath file) 4)
      (let [updated (slurp file)]
        (is (str/includes? updated ";; evidence"))
        (is (str/includes? updated ":durable-replicas 4")))
      (finally
        (.delete file)))))
