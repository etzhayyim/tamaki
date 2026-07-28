(ns kotoba.tamaki.storage-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.storage :as storage]))

(defn temp-dir [prefix]
  (.toFile
   (java.nio.file.Files/createTempDirectory
    prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn policy-for [root candidate]
  {:storage.policy/version 1
   :storage.policy/volume (.getPath root)
   :storage.policy/high-watermark 0.8
   :storage.policy/target-watermark 0.7
   :storage.policy/max-reclaim-bytes-per-tick 1000000
   :storage.policy/min-annex-copies 2
   :storage.policy/allowed-roots [(.getPath root)]
   :storage.policy/candidates [candidate]})

(deftest policy-paths-fail-closed
  (let [root (temp-dir "tamaki-storage-root")
        outside (temp-dir "tamaki-storage-outside")
        candidate {:storage.candidate/id :unsafe
                   :storage.candidate/type :recreatable-directory
                   :storage.candidate/path (.getPath outside)
                   :storage.candidate/recoverability :recreated}]
    (is (thrown? Exception
                 (storage/validate-policy (policy-for root candidate))))
    (is (thrown? Exception
                 (storage/validate-policy
                  (assoc (policy-for root
                                     (assoc candidate
                                            :storage.candidate/path
                                            (.getPath root)))
                         :storage.policy/target-watermark 0.9))))))

(deftest pressure-selects-explicit-recreatable-content
  (let [root (temp-dir "tamaki-storage-plan")
        cache (io/file root "cache")
        _ (.mkdir cache)
        candidate {:storage.candidate/id :cache
                   :storage.candidate/type :recreatable-directory
                   :storage.candidate/path (.getPath cache)
                   :storage.candidate/recoverability :recreated
                   :storage.candidate/requires-no-open-files? false}
        policy (policy-for root candidate)]
    (binding [storage/*command-fn*
              (fn [argv _]
                (if (= "/usr/bin/du" (first argv))
                  {:exit 0 :out "100\tcache\n" :err ""}
                  {:exit 1 :out "" :err ""}))]
      (let [pressured (storage/plan
                       policy
                       {:storage/total-bytes 1000
                        :storage/used-bytes 900
                        :storage/free-bytes 100
                        :storage/usage-ratio 0.9})
            healthy (storage/plan
                     policy
                     {:storage/total-bytes 1000
                      :storage/used-bytes 700
                      :storage/free-bytes 300
                      :storage/usage-ratio 0.7})]
        (is (= :pressure (:storage/status pressured)))
        (is (true? (:storage.candidate/selected?
                    (first (:storage/candidates pressured)))))
        (is (= :healthy (:storage/status healthy)))
        (is (false? (:storage.candidate/selected?
                     (first (:storage/candidates healthy)))))))))

(deftest recreatable-directory-is-deleted-without-following-unselected-paths
  (let [root (temp-dir "tamaki-storage-apply")
        selected (io/file root "selected")
        preserved (io/file root "preserved")
        _ (.mkdir selected)
        _ (.mkdir preserved)
        _ (spit (io/file selected "cache.bin") "cache")
        _ (spit (io/file preserved "work.txt") "work")
        plan {:storage/candidates
              [{:storage.candidate/id :selected
                :storage.candidate/type :recreatable-directory
                :storage.candidate/path (.getPath selected)
                :storage.candidate/bytes 5
                :storage.candidate/selected? true}
               {:storage.candidate/id :preserved
                :storage.candidate/type :recreatable-directory
                :storage.candidate/path (.getPath preserved)
                :storage.candidate/bytes 4
                :storage.candidate/selected? false}]}]
    (binding [storage/*command-fn*
              (fn [_ _] {:exit 0 :out "0\tpath\n" :err ""})]
      (let [outcomes (storage/apply-plan! {} plan)]
        (is (not (.exists selected)))
        (is (.exists preserved))
        (is (= :reclaimed (:storage.outcome/status (first outcomes))))
        (is (= :not-selected
               (:storage.outcome/status (second outcomes))))))))

(deftest annex-drop-requires-every-remote-and-two-copies
  (let [commands (atom [])
        candidate {:storage.candidate/id :dataset
                   :storage.candidate/type :annex-dataset
                   :storage.candidate/path "/private/dataset"
                   :storage.candidate/required-remotes ["b2" "external"]
                   :storage.candidate/min-copies 2
                   :storage.candidate/bytes 100
                   :storage.candidate/selected? true}
        plan {:storage/candidates [candidate]}]
    (binding [storage/*command-fn*
              (fn [argv _]
                (swap! commands conj argv)
                (cond
                  (= ["git" "annex" "fsck" "--from" "b2" "--fast"] argv)
                  {:exit 0 :out "" :err ""}
                  (= ["git" "annex" "fsck" "--from" "external" "--fast"] argv)
                  {:exit 1 :out "" :err "missing"}
                  :else {:exit 0 :out "0\tannex\n" :err ""}))]
      (let [outcome (first (storage/apply-plan!
                            {:storage.policy/min-annex-copies 2} plan))]
        (is (= :blocked (:storage.outcome/status outcome)))
        (is (= :annex-remote-verification-failed
               (:storage.outcome/reason outcome)))
        (is (not-any? #(some #{"drop"} %) @commands))))))
