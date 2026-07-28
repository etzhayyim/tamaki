(ns kotoba.tamaki.ao-fleet-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kotoba.tamaki.ao-fleet :as fleet]))

(def policy
  {:ao.fleet/family :tamaki/etzhayyim
   :ao.fleet/selection {:max-active 2
                        :dispatch-per-reconcile 1
                        :min-score 0.5
                        :revisit-after-ms 3600000}
   :ao.fleet/weights {:open-issue 3.0 :open-pr 2.0
                      :freshness 1.0 :representative 0.1}
   :ao.fleet/runners [:codex :claude]
   :ao.fleet/authority {:issue :radicle-first
                        :integrate :approval-required
                        :cross-ao :blocked}})

(defn ao [repo-name issues prs]
  {:ao/id (str "ao:github:etzhayyim/" repo-name)
   :ao/family :tamaki/etzhayyim
   :ao/given-name repo-name
   :ao/status :active
   :ao/repository {:slug (str "etzhayyim/" repo-name)
                   :name repo-name :fork? false
                   :pushed-at "2026-07-28T00:00:00Z"}
   :ao/signals {:open-issues issues :open-pull-requests prs}})

(defn ready-repo! [workspace repo-name radicle?]
  (let [config (io/file workspace "orgs" "etzhayyim"
                        repo-name ".git" "config")]
    (.mkdirs (.getParentFile config))
    (spit config
          (str "[remote \"origin\"]\n  url = git@example/" repo-name "\n"
               (when radicle?
                 "[remote \"rad\"]\n  url = rad://example\n")))))

(deftest fleet-activates-only-local-radicle-ready-aos-within-wip-bound
  (let [workspace (.toFile
                   (java.nio.file.Files/createTempDirectory
                    "tamaki-ao-fleet-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        _ (ready-repo! workspace "high" true)
        _ (ready-repo! workspace "medium" true)
        _ (ready-repo! workspace "github-only" false)
        registry {:family/id :tamaki/etzhayyim
                  :family/organisms [(ao "medium" 1 0)
                                     (ao "high" 2 1)
                                     (ao "github-only" 10 10)
                                     (ao "absent" 20 20)]}
        projected (fleet/projection
                   policy registry (.getPath workspace) nil
                   1785283200000)]
    (is (= {:family-total 4 :eligible 2 :selected 2
            :dispatch 1 :excluded 2}
           (:ao.fleet/summary projected)))
    (is (= ["ao:github:etzhayyim/high"
            "ao:github:etzhayyim/medium"]
           (mapv :ao/id (:ao.fleet/selected projected))))
    (is (= ["ao:github:etzhayyim/high"]
           (mapv :ao/id (:ao.fleet/dispatch projected))))
    (is (= #{:radicle-unavailable}
           (set (:ao/exclusions
                 (first (filter #(= "ao:github:etzhayyim/github-only"
                                    (:ao/id %))
                                (:ao.fleet/scores projected)))))))))

(deftest generated-loop-is-repository-bound-and-approval-gated
  (let [candidate {:ao/id "ao:github:etzhayyim/example"
                   :ao/repository "etzhayyim/example"
                   :ao/project "/workspace/orgs/etzhayyim/example"}
        spec (fleet/loop-spec policy candidate)]
    (is (= :ao/example (:loop/id spec)))
    (is (= "ao:github:etzhayyim/example" (:loop/ao-id spec)))
    (is (= "/workspace/orgs/etzhayyim/example" (:loop/project spec)))
    (is (false? (:loop/auto-approve spec)))
    (is (re-find #"Radicle issues first" (:loop/objective spec)))
    (is (re-find #"human approval boundary" (:loop/objective spec)))))

(deftest policy-refuses-autonomous-integration
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"approval-required"
       (fleet/validate-policy!
        (assoc-in policy [:ao.fleet/authority :integrate] :autonomous)))))
