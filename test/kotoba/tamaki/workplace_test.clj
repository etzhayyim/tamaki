(ns kotoba.tamaki.workplace-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.tamaki.workplace :as workplace]))

(def assignment
  (workplace/assignment
   {:worker-id "ao:etzhayyim:tamaki"
    :organization "etzhayyim"
    :subject "did:repository:etzhayyim/tamaki"
    :repository "etzhayyim/tamaki"
    :incarnation {:id "Tamaki Hikari" :expires-at 2000}
    :capabilities #{:intent/submit}}))

(deftest workplace-does-not-own-the-organism
  (is (= :external-supervisor (:ao.worker/runtime assignment)))
  (is (= :organism-local (get-in assignment [:ao.worker/authority :memory])))
  (is (= :organism-local (get-in assignment [:ao.worker/authority :lifecycle])))
  (is (= :repository-local (get-in assignment [:ao.worker/authority :source])))
  (is (= :radicle-first (get-in assignment [:ao.worker/authority :issue]))))

(deftest admitted-intent-still-has-organism-gates
  (let [intent {:intent/id "intent-1"
                :intent/organization "etzhayyim"
                :intent/worker "ao:etzhayyim:tamaki"
                :intent/capability :intent/submit
                :intent/expires-at 2000}
        admitted (workplace/intent-decision assignment intent 1000)]
    (is (= :admitted (:intent/status admitted)))
    (is (= :not-executed (:intent/effect-status admitted)))
    (is (= [:incarnation-lease :capability :authority :homeostasis :hil]
           (:intent/next-gates admitted)))
    (is (= :organization-boundary
           (:intent/reason
            (workplace/intent-decision
             assignment (assoc intent :intent/organization "other") 1000))))
    (is (= :capability-not-granted
           (:intent/reason
            (workplace/intent-decision
             assignment (assoc intent :intent/capability :merge) 1000))))
    (is (= :intent-expired
           (:intent/reason
            (workplace/intent-decision assignment intent 2000))))))
