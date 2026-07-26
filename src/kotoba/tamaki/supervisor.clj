(ns kotoba.tamaki.supervisor
  "Human decision and voice-intent boundary for the Tamaki supervisor."
  (:require [clojure.string :as str]
            [hil.core :as hil]
            [kotoba.tamaki.model :as model]
            [kotoba.tamaki.store :as store]))

(defn- run-process [argv]
  (let [process (.start (ProcessBuilder. ^java.util.List argv))
        out (future (slurp (.getInputStream process)))
        err (future (slurp (.getErrorStream process)))
        exit (.waitFor process)]
    {:exit exit :out @out :err @err}))

(defn macos-prompt
  "Native HIL adapter. Speech is output-only; the decision still comes from a
  visible native dialog with Reject as the safe default."
  [{:keys [voice?]}]
  (reify hil/IHumanApproval
    (-request-approval! [_ request]
      (let [text (hil/alert-text request)
            _ (when voice?
                (future (run-process ["/usr/bin/say" text])))
            script (str "button returned of (display dialog "
                        (pr-str text)
                        " with title " (pr-str (:title request))
                        " buttons {\"Dismiss\", \"Reject\", \"Approve\"}"
                        " default button \"Reject\" cancel button \"Dismiss\")")
            result (run-process ["/usr/bin/osascript" "-e" script])
            button (str/trim (:out result))]
        (case button
          "Approve" :approved
          "Reject" :rejected
          :dismissed)))))

(defn record-decision!
  [request decision now]
  (let [run (model/agent-run
             {:goal (str "Human consultation: " (:title request))
              :project "orgs/kotoba-lang/tamaki"
              :mode :external :runner "tamaki-supervisor"
              :model "human" :capabilities #{}}
             now)
        root (store/default-root)]
    (store/append-event! root
                         (model/event run :run/submitted now {:run run}))
    (store/append-event! root
                         (model/event run :supervisor/consulted (inc now)
                                      {:hil/request request
                                       :hil/decision decision}))
    (store/append-event! root
                         (model/event run :run/succeeded (+ now 2)
                                      {:hil/decision decision}))
    {:run-id (:agent.run/id run) :decision decision}))

(defn consult!
  [{:keys [title summary action impact voice?]}]
  (let [request (hil/approval-request
                 (cond-> {:id (str "tamaki-" (System/currentTimeMillis))
                          :title (or title "Tamaki supervisor")
                          :summary summary
                          :action action}
                   impact (assoc :impact impact)))
        decision (hil/request! (macos-prompt {:voice? voice?}) request)]
    (record-decision! request decision (System/currentTimeMillis))))

(defn voice-intent
  "Validate a speech transcript before it becomes an agent goal."
  [transcript]
  (let [transcript (some-> transcript str/trim)]
    (when (str/blank? transcript)
      (throw (ex-info "Voice transcript is empty" {})))
    (when (> (count transcript) 1200)
      (throw (ex-info "Voice transcript is too long" {:limit 1200})))
    (str "Supervisor voice intent: " transcript)))
