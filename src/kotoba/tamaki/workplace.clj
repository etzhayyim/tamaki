(ns kotoba.tamaki.workplace
  "Organization-workplace boundary for a repository-bound artificial organism.

  A workplace may assign responsibility, project redacted activity, and submit
  typed intents. It does not become the organism's process, memory, lifecycle,
  source, issue, or effect authority.")

(def schema "kotoba.ao.worker-assignment.v1")

(def retained-authority
  {:memory :organism-local
   :lifecycle :organism-local
   :source :repository-local})

(defn assignment
  "Build the safe public workplace contract for a Tamaki-family AO."
  [{:keys [worker-id organization subject repository incarnation capabilities
           issue-authority]}]
  (when-not (every? some? [worker-id organization subject repository])
    (throw (ex-info "worker identity, organization, subject, and repository are required"
                    {:type :workplace/invalid-assignment})))
  {:ao.worker/schema schema
   :ao.worker/id worker-id
   :ao.worker/kind :artificial-organism
   :ao.worker/organization organization
   :ao.worker/subject subject
   :ao.worker/repository repository
   :ao.worker/runtime :external-supervisor
   :ao.worker/status :active
   :ao.worker/capabilities (set capabilities)
   :ao.worker/authority (assoc retained-authority
                               :issue (or issue-authority :radicle-first))
   :ao.worker/incarnation incarnation})

(defn intent-decision
  "Admit an organization intent to Tamaki's inbox. This decision deliberately
  does not execute an effect; normal organism gates remain downstream."
  [worker-assignment intent now-ms]
  (let [reason
        (cond
          (not= (:intent/organization intent)
                (:ao.worker/organization worker-assignment))
          :organization-boundary

          (not= (:intent/worker intent) (:ao.worker/id worker-assignment))
          :worker-boundary

          (not (contains? (:ao.worker/capabilities worker-assignment)
                          (:intent/capability intent)))
          :capability-not-granted

          (or (nil? (:intent/expires-at intent))
              (<= (:intent/expires-at intent) now-ms))
          :intent-expired)]
    (if reason
      {:intent/status :rejected :intent/reason reason}
      {:intent/status :admitted
       :intent/id (:intent/id intent)
       :intent/effect-status :not-executed
       :intent/next-gates
       [:incarnation-lease :capability :authority :homeostasis :hil]})))
