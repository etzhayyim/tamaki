(ns kotoba.tamaki.lineage-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.lineage :as lineage]))

(def parent
  (lineage/organism {:given-name "Hikari" :generation 1} 0))

(def healthy
  {:human-agency 0.9 :relational-trust 0.8 :inheritable-learning 0.7
   :future-optionality 0.8 :succession-integrity 0.9})

(deftest organism-has-a-finite-non-extendable-life
  (is (= (* 30 lineage/day-ms) (:organism/expires-at parent)))
  (is (= :active-life (lineage/life-phase parent lineage/day-ms)))
  (is (= :handover (lineage/life-phase parent (* 29 lineage/day-ms))))
  (is (= :expired (lineage/life-phase parent (* 30 lineage/day-ms))))
  (is (thrown? Exception
               (lineage/organism {:given-name "TooLong"
                                  :lifetime-ms (inc lineage/default-lifetime-ms)}
                                 0))))

(deftest reproduction-is-impossible-without-human-consent
  (let [blocked (lineage/succession-plan
                 {:parent parent :child-name "Meguru"
                  :now-ms (* 28 lineage/day-ms)
                  :wellbecoming healthy :memes []})
        approved (lineage/succession-plan
                  {:parent parent :child-name "Meguru"
                   :now-ms (* 28 lineage/day-ms)
                   :wellbecoming healthy :memes []
                   :human-consent? true
                   :consent-signature "human-approved:example"})]
    (is (= :blocked (:succession/status blocked)))
    (is (= :approval-required (:succession/gate blocked)))
    (is (= :approved (:succession/status approved)))
    (is (= "Tamaki"
           (get-in approved [:succession/child :organism/family-name])))
    (is (= 2 (get-in approved [:succession/child :organism/generation])))))

(deftest meme-inheritance-requires-provenance-consent-and-intent
  (let [memes [{:meme/id :care :meme/inheritable? true
                :meme/provenance [:human :tamaki-hikari]
                :meme/consent :family-private}
               {:meme/id :private :meme/inheritable? true}
               {:meme/id :temporary :meme/inheritable? false
                :meme/provenance [:tamaki-hikari]
                :meme/consent :private}]]
    (is (= [:care] (mapv :meme/id (lineage/inheritable-memes memes))))))

(deftest self-other-nonseparation-preserves-human-boundaries
  (testing "low human agency cannot be offset by otherwise strong dimensions"
    (is (= :repair-relationship
           (lineage/action-gate
            {:individual parent :now-ms lineage/day-ms :action :work
             :wellbecoming (assoc healthy :human-agency 0.1)}))))
  (is (pos? (lineage/lineage-vitality healthy))))
