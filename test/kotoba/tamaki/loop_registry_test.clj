(ns kotoba.tamaki.loop-registry-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.loop :as loop]
            [kotoba.tamaki.loop-registry :as reg]))

(defn- temp-spec [m]
  (let [f (java.io.File/createTempFile "tamaki-loop-" ".edn")]
    (spit f (pr-str m))
    (.getAbsolutePath f)))

(deftest validate-and-read-loop-spec
  (let [path (temp-spec
              {:loop/id :demo/maturity
               :loop/objective "raise maturity"
               :loop/project "/tmp/demo"
               :loop/continuous true
               :loop/interval-ms 900000
               :loop/runners ["codex" "claude"]
               :loop/auto-approve true})
        spec (reg/read-spec path)]
    (is (= :demo/maturity (:loop/id spec)))
    (is (= (.getCanonicalPath (io/file "/tmp/demo"))
           (:loop/project spec)))
    (is (= ["codex" "claude"] (:loop/runners spec)))
    (is (true? (:loop/continuous spec)))
    (is (= 900000 (:loop/interval-ms spec)))
    (is (true? (:loop/enabled spec)))
    (let [opts (reg/ensure-options spec)]
      (is (= "raise maturity" (:objective opts)))
      (is (= "codex,claude" (:runners opts)))
      (is (= "demo/maturity" (:spec-id opts)))
      (is (true? (:continuous opts)))
      (is (true? (:auto-approve opts))))))

(deftest weighted-runners-normalize-like-actor-spec
  (let [spec (reg/validate-spec
              {:loop/id :demo/weighted
               :loop/objective "x"
               :loop/project "/tmp"
               :loop/runners [{:runner :codex :weight 2}
                              {:runner :grok :weight 1}]})]
    (is (= ["codex" "grok"] (:loop/runners spec)))))

(deftest workspace-env-resolves-relative-project
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "tamaki-ws-"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        child (io/file root "orgs" "kotoba-lang" "toshokan")]
    (.mkdirs child)
    (with-redefs [reg/env (fn
                            ([k] (reg/env k nil))
                            ([k default]
                             (if (= k "COM_JUNKAWASAKI_ROOT") root default)))]
      (let [spec (reg/validate-spec
                  {:loop/id :toshokan/maturity
                   :loop/objective "さらに成熟度を向上"
                   :loop/project "orgs/kotoba-lang/toshokan"
                   :loop/workspace-env "COM_JUNKAWASAKI_ROOT"})
            resolved (reg/resolve-project spec)]
        (is (= (.getCanonicalPath child) resolved))))))

(deftest compatible-campaign-prefers-spec-id
  (let [spec (reg/validate-spec
              {:loop/id :toshokan/maturity
               :loop/objective "さらに成熟度を向上"
               :loop/project "/repo/toshokan"
               :loop/runners ["codex"]
               :loop/auto-approve true})
        campaign (loop/campaign
                  {:objective "さらに成熟度を向上"
                   :project "/repo/toshokan"
                   :runners ["codex"]
                   :auto-approve true
                   :spec-id "toshokan/maturity"}
                  1)
        other (loop/campaign
               {:objective "other"
                :project "/repo/toshokan"
                :runners ["codex"]
                :spec-id "other/loop"}
               2)]
    (is (true? (reg/compatible-campaign? spec campaign)))
    (is (false? (reg/compatible-campaign? spec other)))))

(deftest discover-skips-example-and-targets-names
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "tamaki-loops-"
                  (make-array java.nio.file.attribute.FileAttribute 0)))]
    (spit (io/file dir "good.edn")
          (pr-str {:loop/id :a/b
                   :loop/objective "o"
                   :loop/project "/tmp/x"
                   :loop/runners ["codex"]}))
    (spit (io/file dir "loop-spec.example.edn")
          (pr-str {:loop/id :skip/me
                   :loop/objective "o"
                   :loop/project "/tmp/x"}))
    (spit (io/file dir "revenue-targets.edn")
          (pr-str {:target/mrr-jpy 1}))
    (let [specs (reg/discover-specs {:dirs [dir]})]
      (is (= 1 (count specs)))
      (is (= :a/b (:loop/id (first specs)))))))

(deftest registered-shipped-specs-load
  (doseq [name ["toshokan-maturity.edn" "revenue-growth.edn"]]
    (let [path (str "loops/" name)]
      (when (.isFile (io/file path))
        (let [spec (reg/read-spec path {:resolve-project? false})]
          (is (keyword? (:loop/id spec)))
          (is (string? (:loop/objective spec)))
          (is (pos-int? (:loop/interval-ms spec)))
          (is (seq (:loop/runners spec))))))))
