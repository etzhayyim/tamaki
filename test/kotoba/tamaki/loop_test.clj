(ns kotoba.tamaki.loop-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.tamaki.loop :as loop]))

(deftest campaign-folds-bounded-cycles
  (let [campaign (loop/campaign {:objective "grow coverage"
                                 :project "/repo"
                                 :max-cycles 2} 1)
        started (loop/loop-event campaign :loop/started 1 {:campaign campaign})
        cycle-1 (loop/loop-event campaign :loop/cycle-started 2 {:loop/cycle 1})
        cycle-2 (loop/loop-event campaign :loop/cycle-started 3 {:loop/cycle 2})
        folded (get (loop/campaigns [started cycle-1 cycle-2])
                    (:tamaki.loop/id campaign))]
    (is (= 2 (:tamaki.loop/cycles folded)))
    (is (= :max-cycles (loop/stop-reason folded)))))

(deftest failures-have-a-circuit-breaker
  (let [campaign (loop/campaign {:objective "improve"
                                 :project "/repo"
                                 :max-failures 1} 1)
        failed (loop/apply-event
                campaign
                (loop/loop-event campaign :loop/cycle-failed 2
                                 {:error "agent failed"}))]
    (is (= 1 (:tamaki.loop/failures failed)))
    (is (= :failed (:tamaki.loop/last-result failed)))
    (is (nil? (:tamaki.loop/current-cycle failed)))
    (is (= :max-failures (loop/stop-reason failed)))))

(deftest terminal-cycle-events-record-durable-outcomes
  (let [campaign (loop/campaign {:id "loop-test"
                                 :objective "improve"
                                 :project "/repo"} 1)
        started (loop/loop-event campaign :loop/started 1 {:campaign campaign})]
    (doseq [[kind expected status]
            [[:loop/cycle-integrated :integrated :active]
             [:loop/cycle-no-change :no-change :active]
             [:loop/cycle-reviewed :reviewed :paused]]]
      (let [folded (loop/campaigns
                    [started
                     (loop/loop-event campaign :loop/cycle-started 2
                                      {:loop/cycle 1})
                     (loop/loop-event campaign kind 3 {:loop/cycle 1})])
            result (get folded "loop-test")]
        (is (= expected (:tamaki.loop/last-result result)))
        (is (= status (:tamaki.loop/status result)))
        (is (nil? (:tamaki.loop/current-cycle result)))))))

(deftest successful-cycle-clears-stale-error
  (let [campaign (loop/campaign {:objective "recover cleanly"
                                 :project "/repo"} 1)
        failed (loop/apply-event
                campaign
                (loop/loop-event campaign :loop/cycle-failed 2
                                 {:error "transient failure"}))]
    (doseq [kind [:loop/cycle-integrated
                  :loop/cycle-no-change
                  :loop/cycle-reviewed]]
      (let [recovered (loop/apply-event
                       failed
                       (loop/loop-event campaign kind 3 {}))]
        (is (nil? (:tamaki.loop/last-error recovered)))))))

(deftest completion-records-stop-reason-and-clears-in-flight-cycle
  (let [campaign (assoc (loop/campaign {:objective "operate safely"
                                        :project "/repo"} 1)
                        :tamaki.loop/current-cycle 2)
        completed (loop/apply-event
                   campaign
                   (loop/loop-event campaign :loop/completed 3
                                    {:reason :max-failures}))]
    (is (= :completed (:tamaki.loop/status completed)))
    (is (= :max-failures (:tamaki.loop/stop-reason completed)))
    (is (nil? (:tamaki.loop/current-cycle completed)))))

(deftest campaign-rejects-invalid-bounds
  (doseq [[field value]
          [[:max-cycles 0]
           [:max-failures 0]
           [:interval-ms -1]]]
    (is (thrown? Exception
                 (loop/campaign {:objective "improve"
                                 :project "/repo"
                                 field value}
                                1)))))

(deftest continuous-campaign-rotates-providers-without-cycle-expiry
  (let [campaign (loop/campaign
                  {:objective "continuously improve"
                   :project "/repo"
                   :runners ["codex" "claude" "claude-zai" "grok"]
                   :continuous true
                   :max-cycles 1}
                  1)]
    (is (nil? (loop/stop-reason
               (assoc campaign :tamaki.loop/cycles 1000))))
    (is (= ["codex" "claude" "claude-zai" "grok" "codex"]
           (mapv #(loop/runner-for-cycle campaign %) (range 1 6))))))
