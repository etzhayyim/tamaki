(ns kotoba.tamaki.finance-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.tamaki.finance :as finance]))

(deftest accounting-observation-requires-a-balanced-bs
  (is (= :finance/observed
         (:tamaki.event/kind
          (finance/event {:org :example :period "2026-07"
                          :bs {:assets 100 :liabilities 40 :equity 60}}
                         1))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"does not balance"
       (finance/event {:org :example :period "2026-07"
                       :bs {:assets 100 :liabilities 40 :equity 50}}
                      1))))
