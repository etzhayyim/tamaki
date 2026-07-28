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
                      1)))
  (is (= :finance/observed
         (:tamaki.event/kind
          (finance/event {:owner {:kind :personal :ref :owner/self}
                          :period "2026-07"
                          :bs {:assets 10 :liabilities 0 :equity 10}}
                         1)))))

(deftest floating-point-decimal-cents-are-not-mistaken-for-an-imbalance
  ;; IEEE-754 double subtraction of ordinary decimal currency totals is
  ;; essentially never exactly 0.0 (e.g. (- 1234.56 987.65 246.91) is
  ;; -2.842...E-14, not 0.0), so a genuinely balanced sheet expressed in
  ;; decimal cents must not be rejected as unbalanced.
  (is (= :finance/observed
         (:tamaki.event/kind
          (finance/event {:org :example :period "2026-07"
                          :bs {:assets 1234.56 :liabilities 987.65
                               :equity 246.91}}
                         1))))
  (is (= :finance/observed
         (:tamaki.event/kind
          (finance/event {:org :example :period "2026-07"
                          :bs {:assets 100.10 :liabilities 40.05
                               :equity 60.05}}
                         1)))))

(deftest a-genuine-one-cent-discrepancy-still-fails-at-large-scale
  ;; The floating-point tolerance must not be so loose that it swallows a
  ;; real accounting error just because the totals are large.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"does not balance"
       (finance/event {:org :example :period "2026-07"
                       :bs {:assets 123456789.12 :liabilities 87654321.34
                            :equity 35802467.79}}
                      1)))
  (is (= :finance/observed
         (:tamaki.event/kind
          (finance/event {:org :example :period "2026-07"
                          :bs {:assets 123456789.12 :liabilities 87654321.34
                               :equity 35802467.78}}
                         1)))))

(deftest blank-period-is-rejected
  ;; An empty string is truthy in Clojure, so a bare `(:period observation)`
  ;; check alone would silently accept it as a valid reporting period.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires :period"
       (finance/event {:org :example :period ""
                       :bs {:assets 10 :liabilities 0 :equity 10}}
                      1))))

(deftest whitespace-only-period-is-rejected
  ;; Same hazard as an empty string: whitespace alone is not a real period.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires :period"
       (finance/event {:org :example :period "   "
                       :bs {:assets 10 :liabilities 0 :equity 10}}
                      1))))

(deftest missing-period-is-rejected
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires :period"
       (finance/event {:org :example} 1))))

(deftest missing-identity-is-rejected
  ;; Neither :org nor :owner/:ref is present — the combined identity check
  ;; fires before the type guards.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"requires :period and :org"
       (finance/event {:period "2026-07"} 1))))

(deftest non-nameable-org-is-rejected-with-a-clear-message
  ;; A numeric :org passes the truthy identity check but would crash inside
  ;; `event` when `clojure.core/name` is called on it. The type guard turns
  ;; that opaque ClassCastException into an actionable validation error.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #":org must be a keyword"
       (finance/event {:org 123 :period "2026-07"} 1))))

(deftest non-nameable-owner-ref-is-rejected-with-a-clear-message
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #":owner/:ref must be a keyword"
       (finance/event {:owner {:ref 123} :period "2026-07"} 1))))

(deftest event-run-encodes-the-reporting-entity
  ;; The durable :tamaki.event/run field namespaces the observation under
  ;; "finance::<entity>" so multiple orgs / owners coexist in one event store.
  (let [org-event (finance/event {:org :example :period "2026-07"} 1)
        owner-event (finance/event {:owner {:ref :owner/self} :period "2026-07"} 1)]
    (is (= "finance::example" (:tamaki.event/run org-event)))
    (is (= "finance::self" (:tamaki.event/run owner-event)))))

(deftest partial-balance-sheet-totals-are-accepted
  ;; When fewer than all three BS totals are supplied, the balance equation
  ;; is not enforced — matching the documented contract that the check fires
  ;; only "when all balance-sheet totals are supplied".
  (is (= :finance/observed
         (:tamaki.event/kind
          (finance/event {:org :example :period "2026-07"
                          :bs {:assets 100 :liabilities 40}} 1))))
  (is (= :finance/observed
         (:tamaki.event/kind
          (finance/event {:org :example :period "2026-07"
                          :bs {:assets 100}} 1)))))