(ns kotoba.tamaki.content-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.content :as content]))

(def spec
  {:content/id :example
   :content/project "/private/source"
   :content/channels
   {:aozora {:publisher :atproto}
    :youtube {:publisher :youtube-data-api-v3}}})

(deftest publication-is-an-explicit-approval-boundary
  (let [artifact {:artifact/id :episode-1
                  :artifact/path "/tmp/episode.mp4"
                  :artifact/stage :publish-ready}]
    (is (= :approval-required
           (:decision (content/publication-plan spec artifact false))))
    (is (false?
         (:executable? (content/publication-plan spec artifact false))))
    (is (:executable? (content/publication-plan spec artifact true)))))

(deftest reaction-selects-a-result-based-next-issue
  (testing "weak retention outranks vanity reach"
    (is (= :improve-retention
           (:next-action
            (content/next-action
             (content/reaction-signals
              {:content/id :example
               :artifact/id :episode-1
               :channel :youtube
               :observed-at 1
               :metrics {:views 1000 :completions 100
                         :likes 100 :comments 10}}))))))
  (testing "healthy content without conversion improves its CTA"
    (is (= :improve-call-to-action
           (:next-action
            (content/next-action
             (content/reaction-signals
              {:content/id :example
               :channel :aozora
               :metrics {:impressions 100 :completions 50
                         :likes 4 :conversions 0}})))))))

(deftest observed-events-fold-into-content-status
  (let [event (content/observation-event
               {:content/id :example
                :channel :aozora
                :observed-at 42
                :metrics {:impressions 10}}
               43)]
    (is (= :improve-retention
           (:next-action (content/status [event] :example))))
    (is (= :await-observation
           (:next-action (content/status [] :example))))))

(deftest collector-does-not-invent-a-missing-provider-snapshot
  (is (= :unavailable
         (:collector/status
          (content/collect
           {:reaction/id :missing
            :reaction/source "/path/that/does/not/exist.edn"})))))

(deftest collector-maps-provider-facts-without-provider-coupling
  (let [file (java.io.File/createTempFile "tamaki-reaction-" ".edn")]
    (spit file (pr-str {:video-id "v1"
                        :observed-at 10
                        :analytics {:views 20 :likes 3}}))
    (let [result
          (content/collect
           {:reaction/id :example/youtube
            :reaction/content-id :example
            :reaction/channel :youtube
            :reaction/source (.getPath file)
            :reaction/artifact-id-path [:video-id]
            :reaction/mappings {:views [:analytics :views]
                                :likes [:analytics :likes]}})]
      (is (= :observed (:collector/status result)))
      (is (= "v1" (get-in result [:observation :artifact/id])))
      (is (= 20 (get-in result [:observation :metrics :views]))))))
