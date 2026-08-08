(ns kotoba.tamaki.world-model-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.world-model :as world-model]))

(def incumbent
  {:world-model/version 1
   :world-model/id "tamaki-activity"
   :world-model/time-step 1
   :world-model/horizon 12
   :world-model/stocks
   {:accepted-knowledge {:initial 10.0
                         :inflows [:accepted-change]
                         :outflows []
                         :units "change"}}
   :world-model/variables
   {:accepted-change {:kind :flow
                      :equation [:* :learning-rate :issue-pressure]
                      :units "change/step"}}
   :world-model/parameters
   {:learning-rate {:value 0.2 :units "1"
                    :bounds [0.0 1.0]}
    :issue-pressure {:value 2.0 :units "change/step"}}})

(def observations
  [{:observation/state {:accepted-knowledge 10.0}
    :observation/action {}
    :observation/next-state {:accepted-knowledge 11.0}}
   {:observation/state {:accepted-knowledge 11.0}
    :observation/action {:issue-pressure 4.0}
    :observation/next-state {:accepted-knowledge 13.0}}])

(deftest executable-stock-flow-model-forecasts-one-step
  (is (= 10.4
         (get-in (world-model/forecast incumbent
                                       {:accepted-knowledge 10.0} {})
                 [:world-model/prediction :accepted-knowledge])))
  (is (= 10.8
         (get-in (world-model/forecast incumbent
                                       {:accepted-knowledge 10.0}
                                       {:issue-pressure 4.0})
                 [:world-model/prediction :accepted-knowledge]))))

(deftest deterministic-selection-falsifies-invalid-and-selects-fitter-model
  (let [selection
        (world-model/select-successor
         incumbent observations
         [{:candidate/id :bad-cycle
           :candidate/level :equation
           :candidate/operations
           [{:op :set-equation :name :accepted-change
             :equation [:+ :accepted-change 1]}]}
          {:candidate/id :learn-faster
           :candidate/level :parameter
           :candidate/operations
           [{:op :set-parameter :name :learning-rate :value 0.5}]}]
         {:complexity-weight 0.001 :min-improvement 0.01})]
    (is (:world-model.selection/accepted? selection))
    (is (= :learn-faster (:world-model.selection/candidate selection)))
    (is (= 0.5 (get-in selection [:world-model.selection/model
                                  :world-model/parameters
                                  :learning-rate :value])))
    (is (false? (get-in selection
                        [:world-model.selection/candidates 0
                         :candidate/valid?])))))

(deftest complexity-penalty-prevents-memorizing-structure
  (let [selection
        (world-model/select-successor
         incumbent observations
         [{:candidate/id :unused-variable
           :candidate/level :structure
           :candidate/operations
           [{:op :add-variable :name :observation-one-exception
             :spec {:kind :aux :equation 99 :units "change"}}]}]
         {:complexity-weight 0.1 :min-improvement 0.0})]
    (is (false? (:world-model.selection/accepted? selection)))
    (is (= incumbent (:world-model.selection/model selection)))))

(deftest xmile-projection-is-standard-shaped-and-inspectable
  (let [xmile (world-model/to-xmile incumbent)]
    (is (re-find #"xmlns=\"http://docs.oasis-open.org/xmile/ns/XMILE/v1.0\"" xmile))
    (is (re-find #"<stock name=\"accepted_knowledge\">" xmile))
    (is (re-find #"<flow name=\"accepted_change\">" xmile))
    (is (re-find #"learning_rate \* issue_pressure" xmile))))

(deftest unknown-references-and-operators-fail-closed
  (testing "dangling causal reference"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"dangling references"
         (world-model/validate
          (assoc-in incumbent
                    [:world-model/variables :accepted-change :equation]
                    [:* :learning-rate :imaginary-signal])))))
  (testing "arbitrary code cannot enter the expression language"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Unsupported world-model operator"
         (world-model/validate
          (assoc-in incumbent
                    [:world-model/variables :accepted-change :equation]
                    [:shell "rm" "anything"]))))))

(deftest physical-and-parameter-constraints-fail-closed
  (testing "a stock cannot silently accumulate an auxiliary with wrong units"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Flow units must match"
         (world-model/validate
          (assoc-in incumbent
                    [:world-model/variables :accepted-change :units]
                    "change")))))
  (testing "candidate parameter updates stay inside declared bounds"
    (let [selection
          (world-model/select-successor
           incumbent observations
           [{:candidate/id :impossible-rate
             :candidate/level :parameter
             :candidate/operations
             [{:op :set-parameter :name :learning-rate :value 4.0}]}]
           {})]
      (is (false? (get-in selection
                          [:world-model.selection/candidates 0
                           :candidate/valid?])))
      (is (false? (:world-model.selection/accepted? selection))))))
