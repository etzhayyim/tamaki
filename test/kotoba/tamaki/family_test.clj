(ns kotoba.tamaki.family-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.family :as family]))

(def spec
  {:family/id :tamaki/etzhayyim
   :family/organization "etzhayyim"
   :family/representative-repository "tamaki"
   :family/membership {:rule :all-owned-repositories
                       :include-archived? true}
   :family/invariant {:repository-per-ao 1 :ao-per-repository 1}
   :family/defaults {:ao/family-name "Tamaki"}})

(def repositories
  [{:name "tamaki" :nameWithOwner "etzhayyim/tamaki"
    :url "https://github.com/etzhayyim/tamaki"
    :visibility "PUBLIC" :isArchived false
    :defaultBranchRef {:name "main"}}
   {:name "archive" :nameWithOwner "etzhayyim/archive"
    :url "https://github.com/etzhayyim/archive"
    :visibility "PUBLIC" :isArchived true
    :defaultBranchRef {:name "main"}}])

(deftest every-repository-becomes-exactly-one-artificial-organism
  (let [registry (family/projection spec repositories 42)
        organisms (:family/organisms registry)]
    (is (= {:total 2 :active 1 :dormant 1 :representatives 1}
           (:family/summary registry)))
    (is (= #{"ao:github:etzhayyim/tamaki"
             "ao:github:etzhayyim/archive"}
           (set (map :ao/id organisms))))
    (is (= "ao:github:etzhayyim/tamaki"
           (:family/representative registry)))
    (is (every? #(= :repository-local (:ao/authority %)) organisms))))

(deftest family-refuses-repositories-outside-its-organization
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"outside"
       (family/projection
        spec
        [{:name "tamaki" :nameWithOwner "kotoba-lang/tamaki"}]
        42))))

(deftest family-registry-round-trips-as-private-local-state
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "tamaki-family-test-"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        registry (family/projection spec repositories 42)]
    (family/write-registry! root registry)
    (is (= registry (family/read-registry root)))))
