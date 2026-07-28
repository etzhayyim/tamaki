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
  (is (not (replication/due?
            config {:replication/at 900} 1000)))
  (is (replication/due?
       config {:replication/at 900} 1900)))

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
