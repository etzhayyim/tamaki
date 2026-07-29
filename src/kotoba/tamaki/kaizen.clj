(ns kotoba.tamaki.kaizen
  "Deterministic, output-based evaluation of Tamaki agent loops."
  (:require [clojure.set :as set]
            [kotoba.tamaki.actor :as actor]))

(def default-window-ms 3600000)
(def default-global-wip-limit 4)
(def default-control-wip-limit 2)
(def minimum-failures-for-global-throttle 2)

(defn failure-category
  "Classify a failed event without treating every failure as an agent-quality
  failure. Unknown evidence remains explicit instead of being guessed."
  [event]
  (let [data (:tamaki.event/data event)
        reason (or (:failure/category data)
                   (:actor/reason data)
                   (:reason data))]
    (cond
      (contains? #{:lease-expired :stale-run} reason) :stale
      (contains? #{:budget-exhausted :token-limit :rate-limit} reason) :budget
      (contains? #{:test-failed :verification-failed} reason) :verification
      (contains? #{:merge-conflict :dirty-worktree :index-lock} reason) :integration
      (contains? #{:approval-required :voice-required :held} reason) :human-gate
      :else :unknown)))

(defn ratio [n d]
  (if (pos? d) (/ (double n) (double d)) 0.0))

(defn evaluate
  ([events runs now-ms] (evaluate events runs now-ms default-window-ms))
  ([events runs now-ms window-ms]
   (let [recent (filter #(>= (:tamaki.event/at %) (- now-ms window-ms))
                        events)
         kinds (frequencies (map :tamaki.event/kind recent))
         started (+ (get kinds :loop/cycle-started 0)
                    (get kinds :run/started 0))
         patches (get kinds :patch/created 0)
         reviews (+ (get kinds :review/independent 0)
                    (get kinds :loop/cycle-reviewed 0))
         integrated (+ (get kinds :patch/integrated 0)
                       (get kinds :loop/cycle-integrated 0))
         failed-events (filter #(contains? #{:run/failed :loop/cycle-failed}
                                            (:tamaki.event/kind %))
                               recent)
         failures (count failed-events)
         failure-categories (frequencies (map failure-category failed-events))
         integrated-results
         (set (keep (fn [event]
                      (when (= :patch/integrated
                               (:tamaki.event/kind event))
                        (when-let [patch
                                   (get-in event
                                           [:tamaki.event/data :patch/id])]
                          (str "result/" patch))))
                    events))
         evaluated-results
         (set (keep #(get-in % [:tamaki.event/data :evaluation/result])
                    (filter (fn [event]
                              (= :result/evaluated
                                 (:tamaki.event/kind event)))
                            events)))
         evaluation-debt
         (set/difference integrated-results evaluated-results)
         stale (filterv #(actor/stale-run? % now-ms) runs)
         start->patch (ratio patches started)
         patch->review (ratio reviews patches)
         review->integrate (ratio integrated reviews)
         failure-pressure (ratio failures (+ started failures))
         recommendations
         (cond-> []
           (seq stale) (conj :heal-stale-runs)
           (seq evaluation-debt) (conj :evaluate-integrated-results)
           (and (pos? reviews) (zero? integrated))
           (conj :heal-review-integration-bottleneck)
           ;; One transient provider, auth, or repository failure must not
           ;; deadlock every implementation lane for the whole evaluation
           ;; window. Repeated failures still trigger the global brake.
           (and (>= failures minimum-failures-for-global-throttle)
                (>= failure-pressure 0.5))
           (conj :throttle-spawn)
           (and (>= started 3) (< start->patch 0.25))
           (conj :redirect-issue-selection)
           (and (zero? patches) (pos? started)) (conj :prune-no-change-loop)
           (and (pos? integrated) (< failure-pressure 0.5)) (conj :continue))
         decision (or (first recommendations) :observe)
         score (max 0.0
                    (min 1.0
                         (- (/ (+ start->patch patch->review
                                  review->integrate)
                               3.0)
                            (* 0.5 failure-pressure))))]
     {:kaizen/actor :tamaki/loop-gardener
      :kaizen/window-ms window-ms
      :kaizen/decision decision
      :kaizen/recommendations recommendations
      :kaizen/score score
      :kaizen/evidence
      {:started started :patches patches :reviews reviews
       :integrated integrated :failures failures
       :failure-categories failure-categories
       :evaluation-debt (vec (sort evaluation-debt))
       :stale-runs (mapv :agent.run/id stale)
       :start->patch start->patch
       :patch->review patch->review
       :review->integrate review->integrate
       :failure-pressure failure-pressure}
      :kaizen/change-authority :blocked
      :kaizen/requires-approval true})))

(defn spawn-admission
  "Connect loop evaluation to actor execution.

  Existing work is never cancelled here. New implementation work is admitted
  only below the global WIP limit. During a review/integration bottleneck,
  review-capable actors are admitted first and their objective is redirected
  to the integration frontier. Observation-only actors remain admissible so
  the control loop cannot blind itself."
  ([evaluation runs spec]
   (spawn-admission evaluation runs spec default-global-wip-limit))
  ([evaluation runs spec wip-limit]
   (let [capabilities (set (:actor/capabilities spec))
         active-runs (filter #(contains? actor/active-statuses
                                         (:agent.run/status %))
                             runs)
         control-run? #(contains?
                        (set (:agent.run/required-capabilities %))
                        :loop-evaluation)
         control-active (count (filter control-run? active-runs))
         work-active (count (remove control-run? active-runs))
         recommendations (set (:kaizen/recommendations evaluation))
         observer? (contains? capabilities :loop-evaluation)
         essential-operations?
         (boolean (some capabilities #{:support-routing :incident-response}))
         review-capable? (or (contains? capabilities :result-evaluation)
                             (contains? capabilities :review)
                             (contains? capabilities :review-observation))
         evaluation-drain? (contains? recommendations
                                      :evaluate-integrated-results)
         drain-review? (boolean
                        (some recommendations
                              [:evaluate-integrated-results
                               :heal-review-integration-bottleneck
                               :redirect-issue-selection
                               :prune-no-change-loop]))
         failure-throttle? (contains? recommendations :throttle-spawn)
         wip-full? (if observer?
                     (>= control-active default-control-wip-limit)
                     (>= work-active wip-limit))
         admitted? (and (not wip-full?)
                        (or observer?
                            essential-operations?
                            (and (not failure-throttle?)
                                 (or (not drain-review?) review-capable?))))
         reason (cond
                  wip-full? :global-wip-limit
                  observer? :control-observer
                  essential-operations? :essential-operations
                  failure-throttle? :failure-pressure
                  (and drain-review? (not review-capable?))
                  :review-integration-drain
                  evaluation-drain? :evaluation-priority
                  drain-review? :review-priority
                  :else :normal)]
     {:admitted? admitted?
      :reason reason
      :global-active (+ work-active control-active)
      :work-active work-active
      :control-active control-active
      :global-wip-limit wip-limit
      :control-wip-limit default-control-wip-limit
      :objective-prefix
      (cond
        (and admitted? essential-operations?)
        "Essential operations mode: work only on a runnable incident or support node from the declared private topology. Preserve evidence, draft safely, and stop at every human or external-effect gate. Do not start acquisition work while failure pressure is elevated.\n"

        (and admitted? drain-review? review-capable?)
        (if evaluation-drain?
          "Priority control mode: do not start a new issue. Evaluate an existing integrated result from source, tests, independent review, authority, safety, and measured observations. Persist only evidence-backed EDN with `tamaki result evaluate`; unknown production impact stays low-confidence and awaits 7/30-day validation.\n"
          "Priority control mode: do not start a new issue. Review the existing integration frontier, verify source and tests, and integrate one compatible result through the declared authority before selecting more work.\n")

        :else nil)})))
