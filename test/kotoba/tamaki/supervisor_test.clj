(ns kotoba.tamaki.supervisor-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.supervisor :as supervisor]))

(deftest voice-intent-is-bounded
  (is (= "Supervisor voice intent: 売上改善の blocker を調査して"
         (supervisor/voice-intent "  売上改善の blocker を調査して  ")))
  (testing "blank and oversized transcripts never enter an agent loop"
    (is (thrown? Exception (supervisor/voice-intent " ")))
    (is (thrown? Exception
                 (supervisor/voice-intent (apply str (repeat 1201 "x")))))))
