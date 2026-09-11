(ns kotoba.tamaki.supervisor-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.model :as model]
            [kotoba.tamaki.store :as store]
            [kotoba.tamaki.supervisor :as supervisor]))

(deftest voice-intent-is-bounded
  (is (= "Supervisor voice intent: 売上改善の blocker を調査して"
         (supervisor/voice-intent "  売上改善の blocker を調査して  ")))
  (testing "blank and oversized transcripts never enter an agent loop"
    (is (thrown? Exception (supervisor/voice-intent " ")))
    (is (thrown? Exception
                 (supervisor/voice-intent (apply str (repeat 1201 "x")))))))

(deftest consultation-records-a-valid-agent-run-lifecycle
  (let [events (atom [])]
    (with-redefs [store/default-root (constantly "ignored")
                  store/append-event! (fn [_ event]
                                        (swap! events conj event))]
      (let [{:keys [run-id decision]}
            (supervisor/record-decision!
             {:id "approval-1" :title "Review" :summary "Ready"
              :action "Integrate"}
             :approved 100)
            run (get (model/fold-events @events) run-id)]
        (is (= :approved decision))
        (is (= :succeeded (:agent.run/status run)))
        (is (= [:run/submitted :run/leased :run/started
                :supervisor/consulted :run/succeeded]
               (mapv :tamaki.event/kind @events)))))))
