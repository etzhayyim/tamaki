(ns kotoba.tamaki.finance
  "Validated accounting observations for local dashboards."
  (:require [clojure.string :as str]))

(defn- nameable?
  "True for values safe to pass to `clojure.core/name`. Keywords, strings,
  and symbols are accepted; other types (numbers, maps, etc.) would throw
  an opaque ClassCastException inside `event` without this guard."
  [value]
  (or (keyword? value) (string? value) (symbol? value)))

(defn- present?
  "True for a non-nil value that, if a string, is also non-blank. Guards
  against a blank or whitespace-only :period silently passing validation --
  Clojure's truthiness treats \"\" as truthy, so a bare `(:period ...)`
  check alone would let an empty reporting period through."
  [value]
  (if (string? value) (not (str/blank? value)) (some? value)))

(defn- approx-zero?
  "True when `delta` is close enough to zero to be floating-point noise from
  IEEE-754 double currency arithmetic rather than a genuine imbalance.
  Subtracting ordinary decimal totals such as 1234.56, 987.65, and 246.91
  almost never lands on exactly 0.0 in double precision, so a bare `zero?`
  check on the balance-sheet delta rejected real, balanced accounting data.
  The tolerance is an absolute floor for near-zero totals, plus a term
  scaled by the magnitude of the inputs and the double ULP, so a genuine
  discrepancy of a cent or more still fails at any realistic scale."
  [delta scale]
  (<= (Math/abs (double delta))
      (max 1.0e-6 (* (double scale) (Math/ulp 1.0) 8))))

(defn validate-observation [observation]
  (when-not (and (present? (:period observation))
                 (or (:org observation)
                     (get-in observation [:owner :ref])))
    (throw (ex-info
            "Finance observation requires :period and :org or :owner/:ref" {})))
  (let [org (:org observation)
        owner-ref (get-in observation [:owner :ref])]
    (when (and org (not (nameable? org)))
      (throw (ex-info "Finance :org must be a keyword, string, or symbol"
                      {:org org})))
    (when (and owner-ref (not (nameable? owner-ref)))
      (throw (ex-info "Finance :owner/:ref must be a keyword, string, or symbol"
                      {:owner-ref owner-ref}))))
  (let [{:keys [assets liabilities equity]} (:bs observation)]
    (when (and (number? assets) (number? liabilities) (number? equity))
      (let [delta (- assets liabilities equity)
            scale (max (Math/abs (double assets))
                       (Math/abs (double liabilities))
                       (Math/abs (double equity)))]
        (when-not (approx-zero? delta scale)
          (throw (ex-info "Balance sheet does not balance"
                          {:assets assets :liabilities liabilities :equity equity
                           :balance-delta delta}))))))
  observation)

(defn event [observation now-ms]
  (let [observation (validate-observation observation)]
    {:tamaki.event/version 1
     :tamaki.event/id (str (random-uuid))
     :tamaki.event/run
     (str "finance::"
          (name (or (:org observation)
                    (get-in observation [:owner :ref]))))
     :tamaki.event/parent nil
     :tamaki.event/kind :finance/observed
     :tamaki.event/at now-ms
     :tamaki.event/data observation}))
