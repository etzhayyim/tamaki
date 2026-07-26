(ns kotoba.tamaki.kaizen
  "Deterministic, output-based evaluation of Tamaki agent loops."
  (:require [kotoba.tamaki.actor :as actor]))

(def default-window-ms 3600000)

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
         failures (+ (get kinds :run/failed 0)
                     (get kinds :loop/cycle-failed 0))
         stale (filterv #(actor/stale-run? % now-ms) runs)
         start->patch (ratio patches started)
         patch->review (ratio reviews patches)
         review->integrate (ratio integrated reviews)
         failure-pressure (ratio failures (+ started failures))
         recommendations
         (cond-> []
           (seq stale) (conj :heal-stale-runs)
           (and (pos? reviews) (zero? integrated))
           (conj :heal-review-integration-bottleneck)
           (>= failure-pressure 0.5) (conj :throttle-spawn)
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
       :stale-runs (mapv :agent.run/id stale)
       :start->patch start->patch
       :patch->review patch->review
       :review->integrate review->integrate
       :failure-pressure failure-pressure}
      :kaizen/change-authority :blocked
      :kaizen/requires-approval true})))
