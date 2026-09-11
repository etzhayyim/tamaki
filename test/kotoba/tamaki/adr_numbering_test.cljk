(ns kotoba.tamaki.adr-numbering-test
  "Guards docs/adr/ against silent ADR-number collisions.

  Tamaki's concurrent runner pool lets multiple independent agents pick the
  next free ADR number at the same time; two pairs of files already collided
  in practice (0002-finite-relational-lineage-and-wellbecoming.md and
  0002-lifecycle-maintenance-and-evidence-preserving-cleanup.md, and
  0005-evidence-gated-result-evaluation.md and
  0005-kototama-wasm-capability-contract.md). A bare citation such as
  ADR-0005 is then genuinely ambiguous between two unrelated accepted
  decisions.

  This test does not forbid a collision outright -- renumbering an
  already-cited, accepted ADR is its own separately reviewable decision --
  but it requires every file that shares a number with another file to say
  so explicitly, so a future collision cannot pass review silently."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [kotoba.lang.text :as str]))

(def adr-dir (io/file "docs" "adr"))

(def collision-marker "ADR number collision:")

(defn- adr-files []
  (->> (.listFiles adr-dir)
       (filter (fn [f] (and (.isFile f) (str/ends-with? (.getName f) ".md"))))
       (sort-by (fn [f] (.getName f)))))

(defn- adr-number [file]
  (second (re-matches #"(\d+)-.*\.md" (.getName file))))

(deftest every-adr-file-has-a-numeric-prefix
  (let [files (adr-files)]
    (is (seq files) "docs/adr must contain at least one ADR file")
    (doseq [file files]
      (is (adr-number file)
          (str (.getName file) " must start with a numeric ADR prefix")))))

(deftest colliding-adr-numbers-are-explicitly-marked
  (testing "any two files sharing a leading number both self-disclose it"
    (let [by-number (group-by adr-number (adr-files))
          collisions (filter (fn [[_ files]] (> (count files) 1)) by-number)]
      (doseq [[number files] collisions]
        (doseq [file files]
          (is (str/includes? (slurp file) collision-marker)
              (str "ADR number " number " collides across "
                   (mapv (fn [f] (.getName f)) files)
                   " -- but " (.getName file)
                   " does not contain the collision marker: "
                   collision-marker)))))))
