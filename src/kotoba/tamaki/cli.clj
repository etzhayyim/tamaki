(ns kotoba.tamaki.cli
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [kotoba.tamaki.adapters :as adapters]
            [kotoba.tamaki.active-inference :as active-inference]
            [kotoba.tamaki.ao-fleet :as ao-fleet]
            [kotoba.tamaki.actor :as actor]
            [kotoba.tamaki.business :as business]
            [kotoba.tamaki.bridge :as bridge]
            [kotoba.tamaki.capability :as capability]
            [kotoba.tamaki.content :as content]
            [kotoba.tamaki.delivery :as delivery]
            [kotoba.tamaki.evolution :as evolution]
            [kotoba.tamaki.finance :as finance]
            [kotoba.tamaki.family :as family]
            [kotoba.tamaki.loop :as agent-loop]
            [kotoba.tamaki.loop-registry :as loop-registry]
            [kotoba.tamaki.lineage :as lineage]
            [kotoba.tamaki.intelligence :as intelligence]
            [kotoba.tamaki.kaizen :as kaizen]
            [kotoba.tamaki.mail :as mail]
            [kotoba.tamaki.maintenance :as maintenance]
            [kotoba.tamaki.model :as model]
            [kotoba.tamaki.physiology :as physiology]
            [kotoba.tamaki.replication :as replication]
            [kotoba.tamaki.runners :as runners]
            [kotoba.tamaki.result-evaluation :as result-evaluation]
            [kotoba.tamaki.service :as service]
            [kotoba.tamaki.store :as store]
            [kotoba.tamaki.storage :as storage]
            [kotoba.tamaki.supervisor :as supervisor]
            [kotoba.tamaki.telemetry :as telemetry]
            [kotoba.tamaki.topology-projection :as topology-projection]
            [kotoba.tamaki.visual :as visual])
  (:gen-class))

(defn now [] (System/currentTimeMillis))

(defn usage []
  (str "tamaki — one CLI for Kotoba agent execution\n\n"
       "Usage:\n"
       "  tamaki submit <goal> --project PATH [--mode local|fleet] [options]\n"
       "  tamaki exec <goal> --project PATH -- <command> [args...]\n"
       "  tamaki run <run-id>\n"
       "  tamaki status [run-id]\n"
       "  tamaki resume <run-id>\n"
       "  tamaki cancel <run-id> [--reason TEXT]\n"
       "  tamaki agents [run-id]\n"
       "  tamaki runners\n"
       "  tamaki swarm <goal> --project PATH [--runners IDS] [--execute]\n"
       "  tamaki issue create|show ...\n"
       "  tamaki work issue <issue-id> --project PATH [--execute]\n"
       "  tamaki deliver <run-id> --issue ID --paths a,b --message TEXT\n"
       "  tamaki review <patch-id> --run RUN-ID --tests EVIDENCE\n"
       "  tamaki integrate <patch-id> --run RUN-ID --issue ID --tests EVIDENCE --approve\n"
       "  tamaki result evaluate|tournament|validate --file FACT.edn\n"
       "  tamaki result status\n"
       "  tamaki memory status|replicate --config REPLICATION.edn [--file OBS.edn]\n"
       "  tamaki loop start|ensure|ensure-all|list|validate|status|stop-active|tick|run ...\n"
       "  tamaki consult <summary> [--title TEXT --action TEXT --impact TEXT --silent]\n"
       "  tamaki mail review --file PRIVATE-DRAFT.edn\n"
       "  tamaki voice <transcript> --project PATH [--runner ID --execute]\n"
       "  tamaki actor validate|status|reconcile SPEC.edn [--execute]\n"
       "  tamaki capability validate|envelope ACTOR.edn\n"
       "  tamaki kpi status [--targets FILE]\n"
       "  tamaki kpi observe --file OBSERVATION.edn [--targets FILE]\n"
       "  tamaki kpi collect --spec COLLECTOR.edn [--targets FILE]\n"
       "  tamaki service status|reconcile --spec SERVICE.edn [--execute]\n"
       "  tamaki content plan --spec LOOP.edn --artifact ARTIFACT.edn [--approve]\n"
       "  tamaki content observe --file REACTION.edn\n"
       "  tamaki content collect --spec REACTION-COLLECTOR.edn\n"
       "  tamaki content status --id CONTENT-ID\n"
       "  tamaki finance observe --file ACCOUNTING.edn\n"
       "  tamaki homeostasis status|tick|authorize --policy POLICY.edn [--file OBS.edn]\n"
       "  tamaki store status|sync\n"
       "  tamaki storage status|reconcile --policy POLICY.edn [--execute]\n"
       "  tamaki maintenance status|cleanup [--execute]\n"
       "  tamaki family status|sync [--spec FAMILY.edn] [--execute]\n"
       "  tamaki fleet status|reconcile [--policy FLEET.edn] [--execute]\n"
       "  tamaki topology import|project --file ROADMAP.edn --project PATH [--execute]\n"
       "  tamaki evolve propose|status|transition|open-patch|open-pr|promote ...\n"
       "  tamaki bridge status|reconcile [--execute]\n"
       "  tamaki nodes [fleet-nodes options]\n"
       "  tamaki tick [fleet-tick options]\n"
       "  tamaki infer <probe|plan|up|down|ps|serve|generate> ...\n"
       "  tamaki murakumo <command> ...\n"
       "  tamaki doctor\n"
       "  tamaki contract\n\n"
       "Options:\n"
       "  --repo OWNER/REPO --pin SHA --node NAME|auto --model MODEL --runner ID\n"
       "  --organism-name NAME [--organism-generation N --organism-parent ID]\n"
       "  --requires git,nbb,clojure --parent RUN-ID --execute\n"))

(defn mail!
  [{:keys [positional options]}]
  (let [[subcommand] positional
        path (:file options)]
    (when-not (and (= "review" subcommand) path)
      (throw (ex-info "Usage: tamaki mail review --file PRIVATE-DRAFT.edn" {})))
    (let [draft (edn/read-string (slurp (io/file path)))
          _ (when-not (contains? mail/send-actions (:action draft))
              (throw (ex-info "Mail review accepts only :mail/send or :mail/reply"
                              {:action (:action draft)})))
          {:mail.review/keys [preview record]} (mail/review draft)
          attachments (or (:attachments preview) [])
          display-summary
          (str "送信アカウント: " (:account preview)
               "\n宛先: " (str/join ", " (:recipients preview))
               "\n件名: " (:subject preview)
               "\n添付: "
               (if (seq attachments)
                 (str/join ", " (map :name attachments))
                 "なし")
               "\n関連 issue: " (or (:related-issue draft) "なし")
               "\n返信 context: " (or (:reply-context draft) "なし")
               "\n\n本文:\n" (:body preview))
          decision
          (:decision
           (supervisor/consult-private!
            {:display-request
             {:id (str "mail-review-" (now))
              :title "Tamaki: メール送信前の内容確認"
              :summary display-summary
              :action "表示された内容をそのまま送信する"
              :impact "Approve後のみ送信可能です。編集すると再確認になります。"}
             :record-request
             {:id (str "mail-review-" (now))
              :title "Tamaki: redacted mail review"
              :summary "A local-private mail draft was reviewed."
              :action "Authorize only the unchanged draft digest."
              :impact (pr-str record)}
             :voice? true}))]
      (prn (mail/approval-receipt draft decision :human/operator))
      (if (= :approved decision) 0 1))))

(declare print-edn)

(defn capability!
  [{:keys [positional]}]
  (let [[subcommand path] positional]
    (when-not (and (#{"validate" "envelope"} subcommand) path)
      (throw
       (ex-info "Usage: tamaki capability validate|envelope ACTOR.edn" {})))
    (let [spec (actor/read-spec path)
          execution (:actor/execution spec)]
      (when-not execution
        (throw (ex-info "ActorSpec has no :actor/execution contract"
                        {:actor/id (:actor/id spec)})))
      (case subcommand
        "validate"
        (do
          (print-edn
           (capability/validate!
            (:actor/capabilities spec) execution))
          0)
        "envelope"
        (do
          (print-edn
           (capability/execution-envelope
            (:actor/id spec) (:actor/capabilities spec) execution))
          0)))))

(defn parse-args
  "A bare `--` ends option parsing: everything after it is returned verbatim as
   `:command`, so `tamaki exec ... -- nbb script.cljs --depth 2` passes the
   subprocess's own flags through instead of swallowing them as tamaki options."
  [args]
  (loop [xs args positional [] options {}]
    (cond
      (empty? xs) {:positional positional :options options :command []}
      (= "--" (first xs)) {:positional positional :options options
                           :command (vec (rest xs))}
      :else
      (let [[x y & more] xs]
        (if (str/starts-with? x "--")
          (if (contains? #{"--execute" "--approve" "--auto-approve" "--voice"
                           "--silent"
                           "--continuous"
                           "--dry-run"
                           "--skip-invalid"} x)
            (recur (rest xs) positional
                   (assoc options (keyword (subs x 2)) true))
            (recur more positional
                   (assoc options (keyword (subs x 2)) y)))
          (recur (rest xs) (conj positional x) options))))))

(defn events [] (store/read-events (store/default-root)))
(defn runs [] (model/fold-events (events)))
(defn run-by-id [id] (get (runs) id))

(defn emit! [run kind data]
  (store/append-event! (store/default-root)
                       (model/event run kind (now) data)))

(defn print-edn [x]
  (pprint/pprint x))

(defn default-business-targets-path []
  (let [private (io/file "actors" "revenue-targets.edn")
        example (io/file "examples" "revenue-targets.example.edn")]
    (.getAbsolutePath (if (.isFile private) private example))))

(defn business-targets [options]
  (business/read-targets
   (or (:targets options) (default-business-targets-path))))

(defn family!
  [{:keys [positional options]}]
  (let [[action] positional
        root (store/default-root)
        spec-path (or (:spec options)
                      "organisms/etzhayyim-family.edn")]
    (case action
      "status"
      (do
        (print-edn
         (if-let [registry (family/read-registry root)]
           (family/public-summary registry)
           {:family/status :unobserved
            :family/spec spec-path}))
        0)

      "sync"
      (let [spec (family/read-spec spec-path)
            organization (:family/organization
                          (family/validate-spec! spec))
            result (delivery/execute!
                    ["gh" "repo" "list" organization
                     "--limit" "1000"
                     "--json"
                     (str "name,nameWithOwner,url,visibility,isArchived,"
                          "defaultBranchRef,pushedAt,updatedAt,issues,"
                          "pullRequests,isFork")])
            _ (delivery/succeeded! result "GitHub family observation")
            registry (family/projection
                      spec
                      (json/parse-string (:out result) true)
                      (now))]
        (when (:execute options)
          (family/write-registry! root registry)
          (store/append-event!
           root
           {:tamaki.event/version 1
            :tamaki.event/id (str (random-uuid))
            :tamaki.event/run "family::etzhayyim"
            :tamaki.event/parent nil
            :tamaki.event/kind :family/reconciled
            :tamaki.event/at (now)
            :tamaki.event/data
            (family/public-summary registry)}))
        (print-edn
         (assoc (family/public-summary registry)
                :family/executed? (boolean (:execute options))))
        0)

      (throw
       (ex-info
        "Usage: tamaki family status|sync [--spec FAMILY.edn] [--execute]"
        {})))))

(defn business-summary
  ([] (business-summary {}))
  ([options] (business/summary (events) (business-targets options)
                               (:domain options))))

(defn kpi!
  [{:keys [positional options]}]
  (case (first positional)
    "status"
    (do (print-edn (business-summary options)) 0)

    "observe"
    (let [path (:file options)]
      (when (str/blank? path)
        (throw (ex-info "kpi observe requires --file OBSERVATION.edn" {})))
      (let [observation (edn/read-string (slurp (io/file path)))
            event (business/event observation (now))]
        (store/append-event! (store/default-root) event)
        (print-edn (business-summary options))
        0))

    "collect"
    (let [path (:spec options)]
      (when (str/blank? path)
        (throw (ex-info "kpi collect requires --spec COLLECTOR.edn" {})))
      (let [spec (telemetry/read-spec path)
            result (telemetry/collect spec (now))
            observation (:observation result)
            event (business/event observation (now))]
        (store/append-event! (store/default-root) event)
        (print-edn
         (assoc result :business
                (business/summary (events)
                                  (business-targets options)
                                  (:collector/domain result))))
        (if (:collector/fresh? result) 0 1)))

    (throw (ex-info "Usage: tamaki kpi status|observe|collect ..." {}))))

(defn service!
  [{:keys [positional options]}]
  (let [action (first positional)
        path (:spec options)]
    (when (str/blank? path)
      (throw (ex-info
              "service status|reconcile requires --spec SERVICE.edn" {})))
    (let [spec (service/read-spec path)
          summary (business-summary
                   (cond-> {:domain (:service/domain spec)}
                     (:service/business-targets spec)
                     (assoc :targets (:service/business-targets spec))))
          topology (service/topology spec summary (now))
          result {:service/id (:service/id spec)
                  :service/domain (:service/domain spec)
                  :business/status (:business/status summary)
                  :topology/file (:service/topology-file spec)
                  :topology/open
                  (count (filter #(= :open (:issue/status %))
                                 (:topology/issues topology)))
                  :topology/walk (service/active-walk topology)}]
      (case action
        "status" (do (print-edn result) 0)
        "reconcile"
        (do
          (when (:execute options)
            (service/write-topology! (:service/topology-file spec) topology)
            (store/append-event! (store/default-root)
                                 (service/event topology (now))))
          (print-edn (assoc result :service/executed?
                            (boolean (:execute options))))
          0)
        (throw (ex-info
                "Usage: tamaki service status|reconcile --spec SERVICE.edn"
                {}))))))

(defn content!
  [{:keys [positional options]}]
  (case (first positional)
    "plan"
    (let [spec-path (:spec options)
          artifact-path (:artifact options)]
      (when (or (str/blank? spec-path) (str/blank? artifact-path))
        (throw (ex-info
                "content plan requires --spec LOOP.edn --artifact ARTIFACT.edn"
                {})))
      (print-edn
       (content/publication-plan
        (content/read-spec spec-path)
        (edn/read-string (slurp (io/file artifact-path)))
        (boolean (:approve options))))
      0)

    "observe"
    (let [path (:file options)]
      (when (str/blank? path)
        (throw (ex-info "content observe requires --file REACTION.edn" {})))
      (let [observation (edn/read-string (slurp (io/file path)))
            event (content/observation-event observation (now))]
        (store/append-event! (store/default-root) event)
        (print-edn (:tamaki.event/data event))
        0))

    "collect"
    (let [path (:spec options)]
      (when (str/blank? path)
        (throw (ex-info
                "content collect requires --spec REACTION-COLLECTOR.edn" {})))
      (let [spec (edn/read-string (slurp (io/file path)))
            result (content/collect spec)]
        (if-let [observation (:observation result)]
          (let [event (content/observation-event observation (now))]
            (store/append-event! (store/default-root) event)
            (print-edn (assoc result :reaction (:tamaki.event/data event)))
            0)
          (do (print-edn result) 1))))

    "status"
    (let [id (:id options)]
      (when (str/blank? id)
        (throw (ex-info "content status requires --id CONTENT-ID" {})))
      (print-edn (content/status (events) (keyword id)))
      0)

    (throw (ex-info "Usage: tamaki content plan|observe|status ..." {}))))

(defn finance!
  [{:keys [positional options]}]
  (case (first positional)
    "observe"
    (let [path (:file options)]
      (when (str/blank? path)
        (throw (ex-info "finance observe requires --file ACCOUNTING.edn" {})))
      (let [observation (edn/read-string (slurp (io/file path)))
            event (finance/event observation (now))]
        (store/append-event! (store/default-root) event)
        (print-edn {:finance/status :observed
                    :finance/org (:org observation)
                    :finance/period (:period observation)})
        0))
    (throw (ex-info "Usage: tamaki finance observe --file ACCOUNTING.edn" {}))))

(defn homeostasis!
  [{:keys [positional options]}]
  (case (first positional)
    "status"
    (let [latest (->> (events)
                      (filter #(= :organism/homeostasis-observed
                                  (:tamaki.event/kind %)))
                      last)]
      (print-edn
       (or (:tamaki.event/data latest)
           {:homeostasis/status :unobserved
            :homeostasis/action :configure-private-policy}))
      0)

    "tick"
    (let [policy-path (:policy options)
          observation-path (:file options)]
      (when (or (str/blank? policy-path) (str/blank? observation-path))
        (throw
         (ex-info
          "homeostasis tick requires --policy POLICY.edn --file OBS.edn"
          {})))
      (let [policy (physiology/read-policy policy-path)
            ;; Disk reserve is a local physiological fact, not operator prose.
            ;; Refresh it on every tick so a stale private observation cannot
            ;; hide exhaustion or keep the organism throttled after cleanup.
            observation
            (assoc (edn/read-string (slurp (io/file observation-path)))
                   :storage-free-bytes
                   (.getUsableSpace (io/file (store/default-root))))
            projection (physiology/decide policy observation (now))]
        (store/append-event! (store/default-root)
                             (physiology/event projection observation (now)))
        (print-edn projection)
        ;; Planning is autonomous; external settlement remains a separate HIL
        ;; capability and is never performed by this command.
        0))

    "authorize"
    (let [observation-path (:file options)]
      (when (str/blank? observation-path)
        (throw
         (ex-info
          "homeostasis authorize requires --file PRIVATE-OBSERVATION.edn"
          {})))
      (let [file (io/file observation-path)
            observation (edn/read-string (slurp file))
            authorized (physiology/renew-human-authority observation (now))
            temporary (io/file (.getParentFile file)
                               (str "." (.getName file) ".tmp"))]
        ;; The observation stays in the operator's private state tree. Rename
        ;; after a complete write so a crash cannot leave a partial EDN fact.
        (spit temporary (str (pr-str authorized) "\n"))
        (java.nio.file.Files/move
         (.toPath temporary)
         (.toPath file)
         (into-array
          java.nio.file.CopyOption
          [java.nio.file.StandardCopyOption/REPLACE_EXISTING
           java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
        (print-edn
         {:homeostasis/authority :renewed
          :human-authority-valid? true
          :human-authority-observed-at
          (:human-authority-observed-at authorized)})
        0))

    (throw
     (ex-info
      "Usage: tamaki homeostasis status|tick|authorize --policy POLICY.edn --file OBS.edn"
      {}))))

(defn store!
  [{:keys [positional]}]
  (case (first positional)
    "status" (do (print-edn (store/readiness)) 0)
    "sync" (let [result (store/sync-federated! (store/default-root))]
             (print-edn result)
             (if (zero? (:replication/failed result)) 0 1))
    (throw (ex-info "Usage: tamaki store status|sync" {}))))

(defn storage!
  [{:keys [positional options]}]
  (let [action (first positional)
        policy-path (:policy options)]
    (when (str/blank? policy-path)
      (throw
       (ex-info
        "storage status/reconcile requires --policy POLICY.edn"
        {})))
    (let [policy (storage/read-policy policy-path)
          before (storage/observe-volume policy)
          plan (storage/plan policy before)]
      (case action
        "status"
        (do (print-edn plan) 0)

        "reconcile"
        (if-not (:execute options)
          (do (print-edn (assoc plan :storage/dry-run true)) 0)
          (let [outcomes (storage/apply-plan! policy plan)
                after (storage/observe-volume policy)
                event (storage/event plan outcomes before after (now))]
            (store/append-event! (store/default-root) event)
            (print-edn (:tamaki.event/data event))
            (if (some #(and (= :blocked (:storage.outcome/status %))
                            (:storage.candidate/selected?
                             (first
                              (filter
                               (fn [candidate]
                                 (= (:storage.outcome/id %)
                                    (:storage.candidate/id candidate)))
                               (:storage/candidates plan)))))
                      outcomes)
              1
              0)))

        (throw
         (ex-info
          "Usage: tamaki storage status|reconcile --policy POLICY.edn [--execute]"
          {}))))))

(defn memory!
  [{:keys [positional options]}]
  (case (first positional)
    "status"
    (do
      (print-edn
       (or (replication/latest-receipt (store/default-root))
           {:replication/status :unobserved
            :replication/action :configure-private-targets}))
      0)

    "replicate"
    (let [config-path (:config options)]
      (when (str/blank? config-path)
        (throw
         (ex-info "memory replicate requires --config REPLICATION.edn" {})))
      (let [receipt
            (replication/reconcile!
             (store/default-root)
             (replication/read-config config-path)
             (now))]
        (when-let [observation-path (:file options)]
          (replication/update-observation!
           observation-path (:replication/durable-replicas receipt)))
        (print-edn receipt)
        (if (= :degraded (:replication/status receipt)) 1 0)))

    (throw
     (ex-info
      "Usage: tamaki memory status|replicate --config REPLICATION.edn [--file OBS.edn]"
      {}))))

(defn maintenance!
  [{:keys [positional options]}]
  (let [action (first positional)
        plan (maintenance/plan (remove nil? (vals (runs))) (now))
        summary (maintenance/summary plan)]
    (case action
      "status"
      (do (print-edn (assoc summary :maintenance/plan plan)) 0)

      "cleanup"
      (let [results (when (:execute options)
                      (maintenance/apply-plan! plan))
            failed (filter #(and (= :remove (:maintenance/disposition %))
                                 (not (:maintenance/applied? %)))
                           results)
            receipt (cond-> (assoc summary
                                   :maintenance/dry-run
                                   (not (:execute options)))
                      results (assoc :maintenance/results results))]
        (when (:execute options)
          (let [removed (count (filter #(and (= :remove
                                                  (:maintenance/disposition %))
                                               (:maintenance/applied? %))
                                       results))
                event-receipt
                (-> receipt
                    ;; The append-only event is control feedback, not an
                    ;; archive of every worktree. Repeating the full evidence
                    ;; list each round made observer projection grow
                    ;; quadratically. The bounded frontier retains actionable
                    ;; provenance; `maintenance status` remains the full view.
                    (dissoc :maintenance/preserved :maintenance/results)
                    (assoc :maintenance/removed removed
                           :maintenance/failed
                           (mapv #(select-keys
                                   %
                                   [:maintenance/run :maintenance/project
                                    :maintenance/reason])
                                 failed)))]
            (store/append-event!
             (store/default-root)
             {:tamaki.event/version 1
              :tamaki.event/id (str (random-uuid))
              :tamaki.event/run "maintenance::git-lifecycle"
              :tamaki.event/parent nil
              :tamaki.event/kind :maintenance/completed
              :tamaki.event/at (now)
              :tamaki.event/data event-receipt})))
        (print-edn receipt)
        (if (seq failed) 1 0))

      (throw
       (ex-info "Usage: tamaki maintenance status|cleanup [--execute]" {})))))

(defn- topology-receipt! [kind data]
  (store/append-event!
   (store/default-root)
   {:tamaki.event/version 1
    :tamaki.event/id (str (random-uuid))
    :tamaki.event/run "topology::edn-forge"
    :tamaki.event/parent nil
    :tamaki.event/kind kind
    :tamaki.event/at (now)
    :tamaki.event/data data}))

(defn topology!
  [{:keys [positional options]}]
  (let [action (first positional)
        path (:file options)
        project (or (:project options) (.getAbsolutePath (io/file ".")))]
    (when (str/blank? path)
      (throw (ex-info "topology requires --file ROADMAP.edn" {})))
    (let [topology (topology-projection/read-topology path)
          rid (:topology/radicle-repo topology)
          github-repo (or (:github-repo options)
                          (:topology/github-repo topology))]
      (case action
        "import"
        (let [radicle (if (str/blank? rid)
                        []
                        (topology-projection/fetch-radicle rid project))
              github (topology-projection/fetch-github github-repo project)
              result (topology-projection/import-plan
                      topology radicle github)
              summary (dissoc result :topology)]
          (when (:execute options)
            (topology-projection/write-topology! path (:topology result))
            (topology-receipt!
             :topology/imported
             (assoc summary :topology/file (.getCanonicalPath (io/file path))
                    :topology/radicle-repo rid
                    :topology/github-repo github-repo)))
          (print-edn
           (assoc summary
                  :topology/file (.getCanonicalPath (io/file path))
                  :topology/dry-run (not (:execute options))))
          0)

        "project"
        (do
          (when (str/blank? rid)
            (throw (ex-info
                    "Radicle projection requires :topology/radicle-repo"
                    {:file path})))
          (let [observed (topology-projection/fetch-radicle rid project)
                plan (topology-projection/radicle-plan topology observed)
                results (when (:execute options)
                          (topology-projection/apply-plan! plan project))
                ok? (or (not (:execute options))
                        (every? :ok? results))
                receipt {:topology/file (.getCanonicalPath (io/file path))
                         :topology/radicle-repo rid
                         :projection/operations (count plan)
                         :projection/ok? ok?}]
            (when (:execute options)
              (topology-receipt!
               :topology/projected
               (assoc receipt
                      :projection/results
                      (mapv #(dissoc % :command) results))))
            (print-edn
             (cond-> (assoc receipt
                            :topology/dry-run (not (:execute options))
                            :projection/plan plan)
               results (assoc :projection/results results)))
            (if ok? 0 1)))

        (throw
         (ex-info
          "Usage: tamaki topology import|project --file ROADMAP.edn --project PATH [--execute]"
          {}))))))

(declare execute-run! submit! require-run!)

(defn consult!
  [{:keys [positional options]}]
  (let [summary (str/join " " positional)]
    (when (str/blank? summary)
      (throw (ex-info "Consultation summary is required" {})))
    (print-edn
     (supervisor/consult!
      {:title (:title options)
       :summary summary
       :action (or (:action options) "Continue the agent loop")
       :impact (:impact options)
       ;; A consultation is a decision boundary, so Tamaki speaks by default.
       ;; --silent remains available for CI and accessibility preferences.
       :voice? (not (:silent options))}))
    0))

(defn voice!
  [{:keys [positional] :as parsed}]
  (let [transcript (str/join " " positional)
        goal (supervisor/voice-intent transcript)]
    (submit! (assoc parsed :positional [goal]))))

(defn reconcile-actor-topology!
  "Run the EDN→Radicle projection at the actor reconciliation boundary."
  [spec execute?]
  (when (and (:actor/issue-topology-file spec)
             (contains? (set (:actor/capabilities spec)) :topology-sync))
    (let [path (:actor/issue-topology-file spec)
          topology (topology-projection/read-topology path)
          rid (:topology/radicle-repo topology)
          observed (topology-projection/fetch-radicle
                    rid (:actor/project spec))
          plan (topology-projection/radicle-plan topology observed)
          results (when execute?
                    (topology-projection/apply-plan!
                     plan (:actor/project spec)))
          summary {:topology/file path
                   :projection/operations (count plan)
                   :projection/dry-run (not execute?)
                   :projection/ok?
                   (or (not execute?) (every? :ok? results))}]
      (when execute?
        (topology-receipt!
         :topology/projected
         (assoc summary :actor/id (:actor/id spec)
                :projection/results
                (mapv #(dissoc % :command) results))))
      (cond-> summary
        (not execute?) (assoc :projection/plan plan)
        results (assoc :projection/results results)))))

(defn actor-status [spec]
  (let [summary (business-summary
                 (cond-> {:domain (:actor/business-domain spec)}
                   (:actor/business-targets spec)
                   (assoc :targets (:actor/business-targets spec))))
        control (business/control-signals summary)
        controlled (assoc spec :actor/control-pressure
                          (if (= :observed (:business/status summary))
                            (:business-pressure control)
                            0.0))]
    (actor/reconcile-plan controlled (remove nil? (vals (runs))) (now))))

(defn attach-business-feedback [spec feedback]
  (if feedback
    (update spec :actor/objective
            str
            "\nLatest durable business control evidence: "
            (pr-str
             (select-keys feedback
                          [:business/status :business/kpis
                           :business/progress :business/control-score]))
            "\nControl signals: "
            (pr-str (business/control-signals feedback))
            ". Select work from measured gaps; do not interpret traffic as "
            "revenue or invent missing conversions.")
    spec))

(defn reconcile-actor!
  [spec execute?]
  (let [initial-runs (remove nil? (vals (runs)))
        _ (doseq [run (filter #(actor/stale-run? % (now)) initial-runs)]
            (emit! run :run/failed
                   {:actor/id (:agent.run/actor run)
                    :actor/reason :lease-expired
                    :failure/category :stale-run
                    :agent.run/previous-status (:agent.run/status run)}))
        ;; Re-fold after global stale recovery so admission observes the
        ;; capacity that was actually reclaimed, including legacy non-actor
        ;; runs that no ActorSpec-specific reconciliation could reap.
        all-runs (remove nil? (vals (runs)))
        loop-evaluation (kaizen/evaluate (events) all-runs (now))
        latest-homeostasis
        (some->> (events)
                 (filter #(= :organism/homeostasis-observed
                             (:tamaki.event/kind %)))
                 last
                 :tamaki.event/data
                 :homeostasis)
        physiology-admission
        (physiology/agent-admission latest-homeostasis spec)
        kaizen-admission
        (kaizen/spawn-admission loop-evaluation all-runs spec)
        admission
        (if (:admitted? physiology-admission)
          (assoc kaizen-admission :physiology physiology-admission)
          (assoc physiology-admission
                 :physiology physiology-admission
                 :kaizen kaizen-admission))
        spec (cond-> spec
               (:objective-prefix admission)
               (update :actor/objective str "\n"
                       (:objective-prefix admission)))
        content-id (:actor/content-id spec)
        feedback (when content-id (content/status (events) content-id))
        business-feedback
        (when (or (:actor/business-domain spec)
                  (= :tamaki/business-portfolio (:actor/type spec))
                  (= :business-domain (:actor/type spec)))
          (business-summary
           (cond-> {:domain (:actor/business-domain spec)}
             (:actor/business-targets spec)
             (assoc :targets (:actor/business-targets spec)))))
        spec (attach-business-feedback spec business-feedback)
        maintenance-feedback
        (when (contains? (set (:actor/capabilities spec)) :loop-evaluation)
          (some->> (events)
                   (filter #(= :maintenance/completed
                               (:tamaki.event/kind %)))
                   last
                   :tamaki.event/data))
        spec (if-let [latest (:latest feedback)]
               (update spec :actor/objective
                       str
                       "\nLatest measured content feedback: "
                       (pr-str (select-keys latest
                                            [:channel :artifact/id :signals
                                             :next-action]))
                       ". Use this evidence when selecting the next issue.")
               spec)
        spec (if maintenance-feedback
               (update spec :actor/objective
                       str
                       "\nLatest deterministic lifecycle maintenance output: "
                       (pr-str
                        (-> (select-keys
                             maintenance-feedback
                             [:maintenance/dispositions
                              :maintenance/preserved-count
                              :maintenance/evidence-groups
                              :maintenance/duplicate-evidence])
                            (assoc
                             :maintenance/conflict-count
                             (count (:maintenance/conflicts
                                     maintenance-feedback))
                             :maintenance/integration-frontier-count
                             (count (:maintenance/integration-frontier
                                     maintenance-feedback))
                             :maintenance/integration-frontier
                             (->> (:maintenance/integration-frontier
                                   maintenance-feedback)
                                  (take 3)
                                  (mapv
                                   #(select-keys
                                     %
                                     [:maintenance/run
                                      :maintenance/project
                                      :maintenance/source
                                      :maintenance/reason
                                      :maintenance/head
                                      :maintenance/paths
                                      :maintenance/duplicates]))))))
                       ". Use this bounded evidence to evaluate or recommend "
                       "the next control action. Do not edit, deliver, "
                       "integrate, or delete preserved evidence.")
               spec)
        topology-sync (reconcile-actor-topology! spec execute?)
        planned (actor-status spec)
        before (if (:admitted? admission)
                 planned
                 (assoc planned :spawn 0))
        existing-count (count (actor/actor-runs spec
                                                (remove nil? (vals (runs)))))
        actor-token (str/replace (str (:actor/id spec)) #"[^A-Za-z0-9]+" "-")]
    (doseq [run-id (:reap before)
            :let [run (run-by-id run-id)
                  status (:agent.run/status run)
                  kind (if (contains? #{:queued :held} status)
                         :run/cancelled :run/failed)]]
      (emit! run kind {:actor/id (:actor/id spec)
                       :actor/reason :lease-expired
                       :agent.run/previous-status status}))
    (doseq [run-id (:cancel before)
            :let [run (run-by-id run-id)]]
      (emit! run :run/cancelled {:actor/id (:actor/id spec)
                                 :actor/reason :scale-down}))
    (let [spawned
          (mapv
           (fn [offset]
             (let [replica-index (+ existing-count offset)
                   base (actor/replica-run spec replica-index (now))
                   profile (runners/profile (:agent.run/runner base))
                   run (assoc base :agent.run/model (:model profile))]
               (emit! run :run/submitted
                      {:run run :actor/id (:actor/id spec)
                       :actor/replica replica-index})
               run))
           (range (:spawn before)))
          executable
          (when execute?
            (->> (concat (:active before) spawned)
                 (filter #(= :queued (:agent.run/status %)))
                 (mapv
                  (fn [run]
                    (let [profile (runners/profile (:agent.run/runner run))
                          configured
                          {:agent.run/source-project
                           (or (:agent.run/source-project run)
                               (:agent.run/project run))
                           :agent.run/project
                           (runners/ensure-run-worktree! run actor-token)
                           :agent.run/model (:model profile)}]
                      (emit! run :run/configured configured)
                      (run-by-id (:agent.run/id run)))))))
          results
          (when (seq executable)
            (->> executable
                 (mapv (fn [run]
                         (future
                           {:run-id (:agent.run/id run)
                            :runner (:agent.run/runner run)
                            :exit (execute-run! run)})))
                 (mapv deref)))
          after (actor-status spec)]
      (print-edn
       (cond-> (assoc after
                      :admission admission
                      :kaizen/decision (:kaizen/decision loop-evaluation)
                      :spawned
                      (mapv #(select-keys
                              % [:agent.run/id :agent.run/replica
                                 :agent.run/runner :agent.run/model])
                            spawned))
         results (assoc :results results)
         topology-sync (assoc :topology-sync topology-sync)))
      (if (and results (some #(not (zero? (:exit %))) results)) 1 0))))

(defn actor!
  [{:keys [positional options]}]
  (let [[action path] positional
        spec (when path (actor/read-spec path))]
    (case action
      "validate" (do (print-edn spec) 0)
      "status" (do (print-edn (actor-status spec)) 0)
      "reconcile" (reconcile-actor! spec (:execute options))
      (throw (ex-info
              "Usage: tamaki actor validate|status|reconcile SPEC.edn"
              {})))))

(defn evolution-candidates []
  (evolution/candidates (events)))

(defn evolution-candidate! [id]
  (or (get (evolution-candidates) id)
      (throw (ex-info "Unknown evolution candidate" {:evolution/id id}))))

(defn append-evolution! [candidate kind data]
  (store/append-event! (store/default-root)
                       (evolution/event candidate kind (now) data)))

(defn evolve-propose!
  [{:keys [positional options]}]
  (let [issue (second positional)
        project (or (:project options) (.getAbsolutePath (io/file ".")))
        objective (:objective options)]
    (when-not (evolution/radicle-id? issue)
      (throw (ex-info "evolve propose requires a full Radicle issue ID" {})))
    (delivery/succeeded!
     (delivery/execute! (delivery/issue-show-command issue) project)
     "Radicle evolution issue lookup")
    (let [status (delivery/succeeded!
                  (delivery/execute! (delivery/git-status-command) project)
                  "evolution clean-tree check")]
      (when (seq (delivery/porcelain-paths (:out status)))
        (throw (ex-info "Evolution proposal requires a clean canonical tree"
                        {:paths (delivery/porcelain-paths (:out status))}))))
    (let [base (-> (delivery/succeeded!
                    (delivery/execute! (delivery/git-head-command) project)
                    "evolution base commit")
                   :out str/trim)
          timestamp (now)
          issue-short (subs issue 0 7)
          branch (str "evolution/rad-" issue-short "-" timestamp)
          worktree (.getAbsolutePath
                    (io/file (.getParentFile (io/file project))
                             (str ".tamaki-" (.getName (io/file project))
                                  "-evolution-" issue-short "-" timestamp)))
          created (delivery/execute!
                   ["git" "-C" project "worktree" "add" "-b"
                    branch worktree base]
                   project)]
      (delivery/succeeded! created "isolated evolution worktree creation")
      (let [candidate (evolution/candidate
                       {:issue issue :objective objective :project project
                        :base-commit base :branch branch :worktree worktree}
                       timestamp)]
        (append-evolution! candidate :evolution/proposed
                           {:candidate candidate})
        (print-edn candidate)
        0))))

(defn parse-evolution-evidence [options]
  (cond-> {}
    (:commit options) (assoc :evolution/commit (:commit options))
    (:patch-id options) (assoc :evolution/patch-id (:patch-id options))
    (:pr-url options) (assoc :evolution/pr-url (:pr-url options))
    (:tests-passed options) (assoc :evolution/tests-passed?
                                   (= "true" (:tests-passed options)))
    (:review-accepted options) (assoc :evolution/review-accepted?
                                     (= "true" (:review-accepted options)))
    (:replay-passed options) (assoc :evolution/replay-passed?
                                   (= "true" (:replay-passed options)))
    (:canary-passed options) (assoc :evolution/canary-passed?
                                   (= "true" (:canary-passed options)))
    (:fitness-before options) (assoc :evolution/fitness-before
                                     (edn/read-string (:fitness-before options)))
    (:fitness-after options) (assoc :evolution/fitness-after
                                    (edn/read-string (:fitness-after options)))))

(defn evolve-transition!
  [{:keys [positional options]}]
  (let [id (second positional)
        status (some-> (nth positional 2 nil) keyword)
        candidate (evolution-candidate! id)
        evidence (parse-evolution-evidence options)]
    ;; Validate before appending; a malformed durable event must never poison
    ;; the candidate fold.
    (let [next-candidate (evolution/transition candidate status (now) evidence)]
      (append-evolution! candidate :evolution/transition
                         {:status status :evidence evidence})
      (print-edn next-candidate)
      0)))

(defn evolve-open-patch!
  [{:keys [positional options]}]
  (let [candidate (evolution-candidate! (second positional))
        worktree (:evolution/worktree candidate)
        title (or (:title options)
                  (str "evolve: " (:evolution/objective candidate)))]
    (when-not (contains? #{:tested :reviewed :canary :awaiting-human}
                         (:evolution/status candidate))
      (throw (ex-info "Radicle patch requires a tested evolution candidate"
                      {:status (:evolution/status candidate)})))
    (let [result (delivery/succeeded!
                  (delivery/execute! (delivery/patch-create-command title)
                                     worktree)
                  "Radicle evolution patch creation")
          patch-id (delivery/output-id result)
          evidence {:evolution/patch-id patch-id
                    :evolution/authority :radicle}]
      (when-not (evolution/radicle-id? patch-id)
        (throw (ex-info "Radicle did not return a full patch ID"
                        {:output (:out result) :error (:err result)})))
      (append-evolution! candidate :evolution/evidence {:evidence evidence})
      (print-edn (merge candidate evidence))
      0)))

(defn evolve-open-pr!
  [{:keys [positional options]}]
  (let [candidate (evolution-candidate! (second positional))
        worktree (:evolution/worktree candidate)
        branch (:evolution/branch candidate)
        title (or (:title options)
                  (str "evolve: " (:evolution/objective candidate)))
        issue (:evolution/issue candidate)
        patch-id (:evolution/patch-id candidate)]
    (when-not (contains? #{:tested :reviewed :canary :awaiting-human}
                         (:evolution/status candidate))
      (throw (ex-info "GitHub mirror PR requires a tested evolution candidate"
                      {:status (:evolution/status candidate)})))
    (delivery/succeeded!
     (delivery/execute! ["git" "push" "-u" "origin" branch] worktree)
     "evolution branch push")
    (let [result (delivery/succeeded!
                  (delivery/execute!
                   ["gh" "pr" "create" "--draft" "--title" title
                    "--body"
                    (str "Evolution candidate `" (:evolution/id candidate)
                         "`.\n\nRadicle-Issue: `" issue "`"
                         (when patch-id
                           (str "\nRadicle-Patch: `" patch-id "`"))
                         "\n\nPromotion remains gated by tests, independent "
                         "review, historical replay, canary, and voice approval.")
                    "--head" branch]
                   worktree)
                  "draft evolution PR creation")
          pr-url (str/trim (:out result))
          evidence {:evolution/pr-url pr-url}]
      (append-evolution! candidate :evolution/evidence
                         {:evidence evidence})
      (print-edn (assoc candidate :evolution/pr-url pr-url))
      0)))

(defn replay-durable-state! []
  (let [durable-events (events)]
    (model/fold-events durable-events)
    (agent-loop/campaigns durable-events)
    (evolution/candidates durable-events)
    true))

(defn evolve-verify!
  [{:keys [positional command]}]
  (let [candidate (evolution-candidate! (second positional))]
    (when-not (= :implemented (:evolution/status candidate))
      (throw (ex-info "Verification requires an implemented candidate"
                      {:status (:evolution/status candidate)})))
    (when (empty? command)
      (throw (ex-info "evolve verify requires -- TEST_COMMAND" {})))
    (let [canonical-head
          (-> (delivery/succeeded!
               (delivery/execute!
                (delivery/git-head-command)
                (:evolution/project candidate))
               "canonical isolation check")
              :out str/trim)]
      (when-not (= canonical-head (:evolution/base-commit candidate))
        (throw (ex-info "Canonical tree moved during candidate evaluation"
                        {:expected (:evolution/base-commit candidate)
                         :actual canonical-head}))))
    (let [tests (delivery/execute! command (:evolution/worktree candidate))
          replay? (try (replay-durable-state!)
                       (catch Exception _ false))
          passed? (and (zero? (:exit tests)) replay?)
          status (if passed? :tested :rejected)
          evidence {:evolution/tests-passed? (zero? (:exit tests))
                    :evolution/replay-passed? replay?
                    :evolution/test-command command
                    :evolution/test-exit (:exit tests)}]
      (append-evolution! candidate :evolution/transition
                         {:status status :evidence evidence})
      (print-edn {:evolution/id (:evolution/id candidate)
                  :status status :evidence evidence})
      (if passed? 0 1))))

(defn evolve-canary!
  [{:keys [positional command]}]
  (let [candidate (evolution-candidate! (second positional))]
    (when-not (= :reviewed (:evolution/status candidate))
      (throw (ex-info "Canary requires an independently reviewed candidate"
                      {:status (:evolution/status candidate)})))
    (when (empty? command)
      (throw (ex-info "evolve canary requires -- CANARY_COMMAND" {})))
    (let [result (delivery/execute! command (:evolution/worktree candidate))
          passed? (zero? (:exit result))
          status (if passed? :canary :rejected)
          evidence {:evolution/canary-passed? passed?
                    :evolution/canary-command command
                    :evolution/canary-exit (:exit result)}]
      (append-evolution! candidate :evolution/transition
                         {:status status :evidence evidence})
      (print-edn {:evolution/id (:evolution/id candidate)
                  :status status :evidence evidence})
      (if passed? 0 1))))

(defn evolve-promote!
  [{:keys [positional]}]
  (let [candidate (evolution-candidate! (second positional))]
    (when-not (evolution/promotion-ready? candidate)
      (throw (ex-info "Evolution candidate is not promotion-ready"
                      {:candidate candidate})))
    (let [{:keys [decision]}
          (supervisor/consult!
           {:title "Tamaki Radicle self-evolution promotion"
            :summary (str "Candidate " (:evolution/id candidate)
                          " passed tests, review, replay and canary.")
            :action (str "Accept and integrate Radicle patch "
                         (:evolution/patch-id candidate))
            :impact "Changes Tamaki's own future behavior."
            :voice? true})]
      (if (= :approved decision)
        (do
          (delivery/succeeded!
           (delivery/execute!
            (delivery/patch-accept-command
             (:evolution/patch-id candidate)
             "Tamaki evolution gates passed: tests, replay, review, canary, fitness and HIL.")
            (:evolution/project candidate))
           "Radicle evolution patch acceptance")
          (delivery/succeeded!
           (delivery/execute! (delivery/git-switch-command "main")
                              (:evolution/project candidate))
           "Radicle canonical branch selection")
          (delivery/succeeded!
           (delivery/execute!
            (delivery/git-merge-patch-command (:evolution/patch-id candidate))
            (:evolution/project candidate))
           "Radicle evolution patch integration")
          (delivery/succeeded!
           (delivery/execute! (delivery/push-canonical-command "main")
                              (:evolution/project candidate))
           "Radicle canonical promotion")
          (delivery/succeeded!
           (delivery/execute!
            (delivery/issue-solve-command (:evolution/issue candidate))
            (:evolution/project candidate))
           "Radicle evolution issue resolution")
          (append-evolution! candidate :evolution/transition
                             {:status :promoted
                              :evidence {:hil/decision decision}})
          (print-edn {:evolution/id (:evolution/id candidate)
                      :result :promoted})
          0)
        (do
          (append-evolution! candidate :evolution/transition
                             {:status :rejected
                              :evidence {:hil/decision decision}})
          (print-edn {:evolution/id (:evolution/id candidate)
                      :result :rejected})
          1)))))

(defn evolve!
  [{:keys [positional] :as parsed}]
  (case (first positional)
    "propose" (evolve-propose! parsed)
    "status" (do
               (print-edn
                (if-let [id (second positional)]
                  (evolution-candidate! id)
                  (->> (vals (evolution-candidates))
                       (sort-by :evolution/updated-at >) vec)))
               0)
    "transition" (evolve-transition! parsed)
    "verify" (evolve-verify! parsed)
    "canary" (evolve-canary! parsed)
    "open-patch" (evolve-open-patch! parsed)
    "open-pr" (evolve-open-pr! parsed)
    "promote" (evolve-promote! parsed)
    (throw (ex-info
            "Usage: tamaki evolve propose|status|transition|verify|open-patch|open-pr|canary|promote"
            {}))))

(defn submit!
  [{:keys [positional options]}]
  (let [goal (first positional)
        mode (keyword (or (:mode options) "local"))
        runner (when-let [id (:runner options)] (runners/profile id))
        requires (if-let [r (:requires options)]
                   (set (map keyword (remove str/blank? (str/split r #","))))
                   (if (= mode :fleet) #{:git :nbb} #{:git}))
        run (model/agent-run
             {:goal goal
              :project (:project options)
              :repo (:repo options)
              :pin (:pin options)
              :mode mode
              :node (keyword (or (:node options) "auto"))
              :model (or (:model options) (:model runner))
              :runner (:id runner)
              :capabilities requires
              :parent (:parent options)}
             (now))]
    (when (and (= mode :local) (str/blank? (:agent.run/project run)))
      (throw (ex-info "Local mode requires --project PATH" {})))
    (when (and (= mode :fleet)
               (some str/blank? [(:agent.run/repo run) (:agent.run/pin run)]))
      (throw (ex-info "Fleet mode requires --repo OWNER/REPO and --pin SHA" {})))
    (store/append-event!
     (store/default-root)
     (model/event run :run/submitted (now) {:run run}))
    (print-edn run)
    (when (:execute options)
      (execute-run! run))))

(defn exec!
  "Register and run a DETERMINISTIC command as an AgentRun.

     tamaki exec <goal> --project PATH -- <command> [args...]

   `local` mode always hands the goal to `kotoba-code`, i.e. to a model. A
   resident data loop (an ingest tick, a scheduled report) has no model in it
   and must not be recorded as if it did -- so `exec` runs the caller's own
   argv, in `--project`, and records the same lifecycle events every other run
   emits: submitted -> leased -> started -> succeeded|failed, carrying the real
   `:agent.run/command` and exit code.

   Mode is `:external`, which `adapters/ready-for?` gates on the event store
   alone (a deterministic tick needs neither kotoba-code nor a fleet node). The
   subprocess's exit code is this command's exit code, so launchd / cron sees
   the truth without parsing output."
  [{:keys [positional options command]}]
  (let [goal (first positional)
        project (:project options)]
    (when (str/blank? goal)
      (throw (ex-info "tamaki exec requires a goal" {})))
    (when (empty? command)
      (throw (ex-info "tamaki exec requires a command after `--`"
                      {:usage "tamaki exec <goal> --project PATH -- <command> [args...]"})))
    (when (str/blank? project)
      (throw (ex-info "tamaki exec requires --project PATH" {})))
    (let [run (model/agent-run
               {:goal goal
                :project project
                :repo (:repo options)
                :mode :external
                :model nil
                :capabilities (if-let [r (:requires options)]
                                (set (map keyword (remove str/blank? (str/split r #","))))
                                #{})
                :parent (:parent options)}
               (now))
          report (adapters/readiness)]
      (when-not (adapters/ready-for? :external report)
        (throw (ex-info "Runtime is not ready" {:mode :external :doctor report})))
      (emit! run :run/submitted {:run run})
      (let [worker (or (System/getenv "TAMAKI_WORKER_ID")
                       (.getHostName (java.net.InetAddress/getLocalHost)))
            leased (model/transition run :leased (now) {:agent.run/worker worker})
            _ (emit! run :run/leased {:agent.run/worker worker})
            argv (vec command)
            _ (emit! leased :run/started {:agent.run/command argv})
            exit (adapters/execute! argv project)
            kind (if (zero? exit) :run/succeeded :run/failed)
            result {:agent.run/exit exit :agent.run/command argv}]
        (emit! (assoc leased :agent.run/status :running) kind result)
        (print-edn (assoc result
                          :agent.run/id (:agent.run/id run)
                          :agent.run/mode :external
                          :agent.run/status (if (zero? exit) :succeeded :failed)))
        exit))))

(defn write-work! [run]
  (let [dir (io/file (store/default-root) "work")
        f (io/file dir (str (:agent.run/id run) ".edn"))]
    (.mkdirs dir)
    (spit f (pr-str (adapters/fleet-work run)))
    (.getAbsolutePath f)))

(defn execute-run!
  [run]
  (when-not run
    (throw (ex-info "Unknown run" {})))
  (when (model/terminal-statuses (:agent.run/status run))
    (throw (ex-info "Terminal run cannot execute"
                    {:run-id (:agent.run/id run)
                     :status (:agent.run/status run)})))
  (let [report (adapters/readiness)
        mode (:agent.run/mode run)
        runner (when-let [id (:agent.run/runner run)] (runners/profile id))]
    (when-not (adapters/ready-for? mode report)
      (throw (ex-info "Runtime is not ready" {:mode mode :doctor report})))
    (let [leased (model/transition run :leased (now)
                                   {:agent.run/worker
                                    (or (:id runner)
                                        (System/getenv "TAMAKI_WORKER_ID")
                                        (.getHostName (java.net.InetAddress/getLocalHost)))})
          _ (emit! run :run/leased
                   (select-keys leased [:agent.run/worker :agent.run/node]))
          argv (if (= mode :fleet)
                 (adapters/fleet-command leased (write-work! leased))
                 (adapters/local-command leased))
          _ (emit! leased :run/started {:agent.run/command argv})
          exit (binding [adapters/*unset-process-env*
                         (vec (:unset-env runner))
                         adapters/*process-env*
                         (cond-> (merge
                                  {"KC_LOOP_ID" (:agent.run/id run)
                                   "KC_SESSION" (:agent.run/id run)
                                   "KC_WORKER_ID" (:agent.run/worker leased)
                                   "KC_RUN_TIMEOUT_MS"
                                   (str (get-in run [:agent.run/budget :deadline-ms]
                                                1200000))
                                   "KC_LEASE_TTL_MS"
                                   (str (get-in run [:agent.run/budget :deadline-ms]
                                                1200000))
                                   "KC_SUBSCRIPTION_TIMEOUT_MS"
                                   (str (min 900000
                                             (get-in run
                                                     [:agent.run/budget :deadline-ms]
                                                     1200000)))
                                   "KC_PROCESS_TIMEOUT_MS"
                                   (str (get-in run
                                                [:agent.run/budget :test-timeout-ms]
                                                180000))}
                                  (:env runner))
                           ;; Only independent-review / observe-only runs may
                           ;; force DONE with a clean tree. Improvement cycles
                           ;; must edit and commit (see Radicle issue 4319650).
                           (:agent.run/require-done-no-edit? run)
                           (assoc "KC_REQUIRE_DONE_NO_EDIT" "1"))
                         adapters/*activity-fn*
                         (fn [line]
                           (let [[kind detail stream]
                                 (cond
                                   (str/includes? line "[model:start]")
                                   [:model/token-processing :started :model]
                                   (str/includes? line "[model:end]")
                                   [:model/token-processing :completed :model]
                                   (str/includes? line "[tool:start]")
                                   [:tool/started :running :tool]
                                   (str/includes? line "[tool:end]")
                                   [:tool/completed :completed :tool]
                                   :else [:agent/output :streaming :output])]
                             (emit! leased :agent/activity
                                    (cond-> {:activity/kind kind
                                             :activity/state detail
                                             :activity/agent
                                             (:agent.run/id leased)
                                             :activity/worker
                                             (:agent.run/worker leased)
                                             :activity/stream stream
                                             :activity/text
                                             (subs line 0 (min 500 (count line)))}
                                      (str/includes? line "[model:usage]")
                                      (merge
                                       (into {}
                                             (keep
                                              (fn [[_ key value]]
                                                (when-let [n (parse-long value)]
                                                  [(keyword "usage" key) n])))
                                             (re-seq
                                              #"(input|output|cache-read|cache-write)=([0-9]+)"
                                              line)))))))]
                 (adapters/execute! argv
                                    (when (= mode :fleet)
                                      (adapters/sibling "kotoba-fleet"))))
          kind (if (zero? exit) :run/succeeded :run/failed)
          result {:agent.run/exit exit
                  :agent.run/command argv}]
      (emit! (assoc leased :agent.run/status :running) kind result)
      (print-edn (assoc result :agent.run/id (:agent.run/id run)
                       :agent.run/status (if (zero? exit) :succeeded :failed)))
      exit)))

(defn runners! []
  (print-edn
   {:runners
    (mapv (fn [profile]
            (assoc (runners/safe-profile profile)
                   :available
                   (adapters/command-exists?
                    (case (:kind profile)
                      :codex "codex"
                      :claude-zai "claude-zai"
                      :grok "grok"
                      "claude"))))
          (runners/profiles))}))

(defn swarm!
  [{:keys [positional options]}]
  (let [goal (first positional)
        project (:project options)
        profiles (runners/selected (:runners options))
        swarm-id (subs (str (random-uuid)) 0 8)]
    (when (str/blank? goal)
      (throw (ex-info "swarm requires a goal" {})))
    (when (str/blank? project)
      (throw (ex-info "swarm requires --project PATH" {})))
    (let [runs
          (mapv
           (fn [profile]
             (let [worktree (if (:execute options)
                              (runners/prepare-worktree!
                               project swarm-id (:id profile))
                              project)
                   run (model/agent-run
                        {:goal goal :project worktree :source-project project
                         :mode :local
                         :model (:model profile) :runner (:id profile)
                         :capabilities #{:git}
                         :parent (str "swarm-" swarm-id)}
                        (now))]
               (emit! run :run/submitted {:run run :swarm/id swarm-id})
               run))
           profiles)]
      (if (:execute options)
        (let [results
              (->> runs
                   (mapv (fn [run]
                           (future
                             {:run-id (:agent.run/id run)
                              :runner (:agent.run/runner run)
                              :project (:agent.run/project run)
                              :exit (execute-run! run)})))
                   (mapv deref))]
          (print-edn {:swarm/id swarm-id :results results})
          (if (every? #(zero? (:exit %)) results) 0 1))
        (do
          (print-edn
           {:swarm/id swarm-id
            :runs (mapv #(select-keys
                          % [:agent.run/id :agent.run/runner
                             :agent.run/model :agent.run/project])
                        runs)})
          0)))))

(defn status!
  [id]
  (if id
    (print-edn (or (run-by-id id)
                   (throw (ex-info "Unknown run" {:run-id id}))))
    (print-edn (->> (vals (runs))
                    (sort-by :agent.run/updated-at >)
                    (mapv #(select-keys %
                                        [:agent.run/id :agent.run/status
                                         :agent.run/mode :agent.run/node
                                         :agent.run/runner :agent.run/model
                                         :agent.run/goal :agent.run/updated-at]))))))

(defn resume!
  [id]
  (let [run (run-by-id id)]
    (when-not (and run (model/resumable? run))
      (throw (ex-info "Run is not resumable"
                      {:run-id id :status (:agent.run/status run)})))
    (when (= :local (:agent.run/mode run))
      (let [exit (adapters/execute!
                  (adapters/local-resume-command run))]
        (when-not (zero? exit)
          (throw (ex-info "Underlying agent runtime could not resume"
                          {:run-id id :exit exit})))))
    (emit! run :run/requeued {:agent.run/resume-from (:agent.run/status run)})
    (execute-run! (run-by-id id))))

(defn cancel!
  [id reason]
  (let [run (require-run! id)]
    (when (model/terminal-statuses (:agent.run/status run))
      (throw (ex-info "Terminal run cannot be cancelled"
                      {:run-id id :status (:agent.run/status run)})))
    (emit! run :run/cancelled
           {:agent.run/cancel-reason (or reason "operator-requested")})
    (print-edn (run-by-id id))
    0))

(defn agents!
  [root-id]
  (let [all (vals (runs))
        children (group-by :agent.run/parent all)
        roots (if root-id
                [(run-by-id root-id)]
                (get children nil))]
    (letfn [(tree [run]
              {:run (select-keys run [:agent.run/id :agent.run/status
                                      :agent.run/goal :agent.run/node])
               :children (mapv tree (get children (:agent.run/id run)))})]
      (print-edn (mapv tree (remove nil? roots))))))

(defn doctor! []
  (let [report (adapters/readiness)]
    (print-edn report)
    (if (every? :ok? (vals report)) 0 1)))

(defn passthrough!
  [argv cwd]
  (adapters/execute! argv cwd))

(defn require-run! [id]
  (or (run-by-id id)
      (throw (ex-info "Unknown run" {:run-id id}))))

(defn receipt! [run kind data]
  (emit! run kind (assoc data :receipt/version 1)))

(defn append-result-event! [kind data]
  (store/append-event!
   (store/default-root)
   {:tamaki.event/version 1
    :tamaki.event/id (str (random-uuid))
    :tamaki.event/run (str "result::"
                           (or (:evaluation/result data)
                               (:validation/result data)
                               (:tournament/issue data)
                               "unknown"))
    :tamaki.event/parent nil
    :tamaki.event/kind kind
    :tamaki.event/at (now)
    :tamaki.event/data data}))

(defn- read-edn-fact [path]
  (when (str/blank? path)
    (throw (ex-info "--file FACT.edn is required" {})))
  (let [file (io/file path)]
    (when-not (.isFile file)
      (throw (ex-info "EDN fact file not found" {:path path})))
    (edn/read-string (slurp file))))

(defn result-status []
  (let [result-events
        (filter #(contains? #{:result/evaluated
                             :result/tournament-recorded
                             :result/validated
                             :result/regressed}
                           (:tamaki.event/kind %))
                (events))]
    {:result/evaluations
     (->> result-events
          (filter #(= :result/evaluated (:tamaki.event/kind %)))
          (mapv :tamaki.event/data))
     :result/tournaments
     (->> result-events
          (filter #(= :result/tournament-recorded
                      (:tamaki.event/kind %)))
          (mapv :tamaki.event/data))
     :result/validations
     (->> result-events
          (filter #(contains? #{:result/validated :result/regressed}
                              (:tamaki.event/kind %)))
          (mapv :tamaki.event/data))}))

(defn result!
  [{:keys [positional options]}]
  (let [[action] positional]
    (case action
      "evaluate"
      (let [evaluation
            (result-evaluation/evaluate
             (read-edn-fact (:file options)) (now))]
        (append-result-event! :result/evaluated evaluation)
        (print-edn evaluation)
        0)

      "tournament"
      (let [tournament
            (result-evaluation/tournament
             (read-edn-fact (:file options)))]
        (append-result-event! :result/tournament-recorded tournament)
        (print-edn tournament)
        0)

      "validate"
      (let [validation
            (result-evaluation/validation
             (read-edn-fact (:file options)) (now))
            kind (if (:validation/regression? validation)
                   :result/regressed :result/validated)]
        (append-result-event! kind validation)
        (print-edn validation)
        0)

      "status"
      (do (print-edn (result-status)) 0)

      (throw
       (ex-info
        "Usage: tamaki result evaluate|tournament|validate --file FACT.edn; tamaki result status"
        {})))))

(defn append-loop-event! [campaign kind data]
  (store/append-event! (store/default-root)
                       (agent-loop/loop-event campaign kind (now) data)))

(defn campaigns [] (agent-loop/campaigns (events)))
(defn campaign-by-id [id] (get (campaigns) id))

(defn parse-long-option [options key default]
  (if-let [value (get options key)]
    (try (Long/parseLong value)
         (catch Exception _
           (throw (ex-info (str "--" (name key) " must be an integer")
                           {:option key :value value}))))
    default))

(defn run-process! [run argv operation]
  (delivery/succeeded!
   (delivery/execute! argv (:agent.run/project run)) operation))

(defn issue! [{:keys [positional options]}]
  (let [[action value] positional
        run (when-let [id (:run options)] (require-run! id))]
    (case action
      "create"
      (let [result (delivery/succeeded!
                    (delivery/execute!
                     (delivery/issue-create-command value (:description options))
                     (:project options))
                    "Radicle issue creation")
            issue-id (delivery/output-id result)
            receipt {:issue/id issue-id :issue/title value}]
        (when run (receipt! run :issue/discovered receipt))
        (print-edn receipt)
        0)

      "show"
      (let [result (delivery/succeeded!
                    (delivery/execute! (delivery/issue-show-command value)
                                       (:project options))
                    "Radicle issue discovery")
            receipt {:issue/id value :issue/observed true}]
        (when run (receipt! run :issue/discovered receipt))
        (print-edn (assoc receipt :issue/output (:out result)))
        0)

      (throw (ex-info "Usage: tamaki issue create|show ..." {})))))

(defn work-issue! [{:keys [positional options]}]
  (let [issue-id (second positional)
        project (:project options)]
    (when (str/blank? project)
      (throw (ex-info "work issue requires --project PATH" {})))
    (let [observed (delivery/succeeded!
                  (delivery/execute! (delivery/issue-show-command issue-id) project)
                  "Radicle issue discovery")
        goal (or (:goal options)
                 (str "Implement Radicle issue " issue-id "\n\n" (:out observed)))
        run (model/agent-run {:goal goal :project project :mode :local
                              :model (:model options)
                              :capabilities #{:git :radicle}} (now))]
      (store/append-event! (store/default-root)
                           (model/event run :run/submitted (now) {:run run}))
      (receipt! run :issue/discovered {:issue/id issue-id})
      (print-edn run)
      (if (:execute options) (execute-run! run) 0))))

(defn deliver! [{:keys [positional options]}]
  (let [run (require-run! (first positional))
        issue-id (:issue options)
        paths (some-> (:paths options) (str/split #","))
        paths (vec (remove str/blank? paths))
        message (:message options)]
    (when-not (= :succeeded (:agent.run/status run))
      (throw (ex-info "Only a succeeded AgentRun may be delivered"
                      {:run-id (:agent.run/id run)
                       :status (:agent.run/status run)})))
    (when (str/blank? issue-id)
      (throw (ex-info "deliver requires --issue ISSUE-ID" {})))
    (when (empty? paths)
      (throw (ex-info "deliver requires explicit --paths a,b" {})))
    (when (str/blank? message)
      (throw (ex-info "deliver requires --message TEXT" {})))
    (let [status (run-process! run (delivery/git-status-command) "git status")
          changed (set (delivery/porcelain-paths (:out status)))
          owned (set paths)
          outside (vec (sort (remove owned changed)))
          dirty? (seq changed)]
      (when (seq outside)
        (throw (ex-info "Working tree contains paths outside this delivery"
                        {:outside-paths outside :owned-paths paths})))
      (when dirty?
        (run-process! run (delivery/git-add-command paths) "git add")
        (run-process! run (delivery/git-commit-command message) "git commit"))
      (let [head (-> (run-process! run (delivery/git-head-command) "git rev-parse")
                     :out str/trim)
            _ (receipt! run :commit/created
                        {:commit/id head :commit/created? dirty?
                         :issue/id issue-id})
            pushed (run-process! run (delivery/patch-create-command message)
                                 "Radicle patch creation")
            patch-id (delivery/output-id pushed)
            receipt {:patch/id patch-id :commit/id head :issue/id issue-id
                     :patch/ref "HEAD:refs/patches"}]
        (receipt! run :patch/created receipt)
        (print-edn receipt)
        0))))

(defn review! [{:keys [positional options]}]
  (let [patch-id (first positional)
        run (require-run! (:run options))
        evidence (:tests options)]
    (when (str/blank? evidence)
      (throw (ex-info "review requires --tests TEST-EVIDENCE" {})))
    (let [shown (run-process! run (delivery/patch-show-command patch-id)
                              "Radicle patch show")
          diffed (run-process! run (delivery/patch-diff-command patch-id)
                               "Radicle patch diff")
          receipt {:patch/id patch-id :review/tests evidence
                   :review/show (subs (:out shown) 0 (min 2000 (count (:out shown))))
                   :review/diff-bytes (count (:out diffed))}]
      (receipt! run :review/observed receipt)
      (print-edn receipt)
      0)))

(declare patch-commit-id)

(defn integrate! [{:keys [positional options]}]
  (let [patch-id (first positional)
        run (require-run! (:run options))
        issue-id (:issue options)
        evidence (:tests options)
        branch (or (:branch options) "main")]
    (when-not (:approve options)
      (throw (ex-info "Integration requires explicit --approve"
                      {:patch/id patch-id})))
    (when-not (some #(and (= (:agent.run/id run) (:tamaki.event/run %))
                          (= :review/observed (:tamaki.event/kind %))
                          (= patch-id (get-in % [:tamaki.event/data :patch/id])))
                    (events))
      (throw (ex-info "Integration requires an observed review"
                      {:patch/id patch-id})))
    (when (str/blank? evidence)
      (throw (ex-info "Integration requires --tests TEST-EVIDENCE" {})))
    (when (str/blank? issue-id)
      (throw (ex-info "Integration requires --issue ISSUE-ID" {})))
    (run-process! run (delivery/patch-accept-command patch-id evidence)
                  "Radicle patch acceptance")
    (run-process! run (delivery/git-switch-command branch) "git switch")
    (let [head (-> (run-process! run (delivery/git-head-command)
                                 "git rev-parse")
                   :out str/trim)
          reviewed-commit (patch-commit-id (:agent.run/id run) patch-id)]
      (when-not (and reviewed-commit
                     (zero? (:exit
                             (delivery/execute!
                              (delivery/git-ancestor-command reviewed-commit head)
                              (:agent.run/project run)))))
        (run-process! run (delivery/git-merge-patch-command patch-id)
                      "git merge")))
    (run-process! run (delivery/push-canonical-command branch)
                  "Radicle canonical push")
    (run-process! run (delivery/issue-solve-command issue-id)
                  "Radicle issue resolution")
    (let [receipt {:patch/id patch-id :integration/approved true
                   :issue/id issue-id :issue/state :solved
                   :integration/branch branch :review/tests evidence}]
      (receipt! run :patch/integrated receipt)
      (print-edn receipt)
      0)))

(defn loop-spec-from-options
  "Load a LoopSpec when --spec PATH or a positional *.edn path is present.
  Returns nil when the caller is using ad-hoc CLI flags only."
  [{:keys [positional options]}]
  (let [path (or (:spec options)
                 (first (filter #(and (string? %)
                                      (str/ends-with? % ".edn"))
                                positional)))]
    (when-not (str/blank? path)
      (loop-registry/read-spec path))))

(defn merge-loop-options
  "Compose ensure/start options: LoopSpec is the base; CLI flags override."
  [parsed]
  (if-let [spec (loop-spec-from-options parsed)]
    (loop-registry/ensure-options spec (:options parsed))
    (:options parsed)))

(defn start-loop-with-options!
  "Start a campaign from a fully resolved options map (not raw CLI parse)."
  [options]
  (let [created-at (now)
        individual
        (when-let [given-name (:organism-name options)]
          (lineage/organism
           {:given-name given-name
            :generation (parse-long-option options :organism-generation 1)
            :parent (:organism-parent options)}
           created-at))
        profiles (cond
                   (:runners options) (runners/selected (:runners options))
                   (:runner options) [(runners/profile (:runner options))]
                   :else [])
        runner (first profiles)
        campaign (agent-loop/campaign
                  {:objective (:objective options)
                   :project (:project options)
                   :model (or (:model options) (:model runner))
                   :runner (:id runner)
                   :runners (mapv :id profiles)
                   :max-cycles (parse-long-option options :max-cycles 10)
                   :interval-ms (parse-long-option options :interval-ms 60000)
                   :max-failures (parse-long-option options :max-failures 3)
                   :auto-approve (boolean (:auto-approve options))
                   :continuous (boolean (:continuous options))
                   :organism individual
                   :ao (:ao-id options)
                   :spec-id (:spec-id options)
                   :spec-path (:spec-path options)}
                  created-at)]
    (append-loop-event! campaign :loop/started {:campaign campaign})
    (print-edn campaign)
    0))

(defn start-loop!
  "Start from a CLI parse map (`:options` / optional `:positional` + `--spec`)."
  [parsed]
  (start-loop-with-options!
   (if (and (map? parsed) (contains? parsed :positional))
     (merge-loop-options parsed)
     (or (:options parsed) parsed))))

(defn ensure-loop!
  "Return one canonical campaign for the requested supervisor configuration.
  Prefer --spec PATH (EDN LoopSpec). Stale active campaigns on the same
  project are durably completed instead of accumulating after launchd restarts."
  [parsed]
  (let [options (if (and (map? parsed) (contains? parsed :positional))
                  (merge-loop-options parsed)
                  (or (:options parsed) parsed))
        profiles (cond
                   (:runners options) (runners/selected (:runners options))
                   (:runner options) [(runners/profile (:runner options))]
                   :else [])
        expected-runners (mapv :id profiles)
        active (->> (vals (campaigns))
                    (filter #(= :active (:tamaki.loop/status %))))
        compatible?
        (fn [campaign]
          (if-let [spec-id (:spec-id options)]
            (and (= (loop-registry/spec-id-str spec-id)
                    (loop-registry/spec-id-str (:tamaki.loop/spec-id campaign)))
                 (= (:project options) (:tamaki.loop/project campaign)))
            (let [individual (:tamaki.loop/organism campaign)]
              (and (= (:project options) (:tamaki.loop/project campaign))
                   (= (:objective options) (:tamaki.loop/objective campaign))
                   (= expected-runners (:tamaki.loop/runners campaign))
                   (= (boolean (:auto-approve options))
                      (:tamaki.loop/auto-approve campaign))
                   (= (:organism-name options)
                      (:organism/given-name individual))
                   (= (parse-long-option options :organism-generation 1)
                      (:organism/generation individual))
                   (= (:organism-parent options)
                      (:organism/parent individual))))))
        canonical (last (sort-by :tamaki.loop/updated-at
                                 (filter compatible? active)))]
    (doseq [campaign active
            :when (and (not= (:tamaki.loop/id campaign)
                             (:tamaki.loop/id canonical))
                       (if-let [spec-id (:spec-id options)]
                         ;; Registry-backed: only retire duplicate instances of
                         ;; this LoopSpec so other EDN loops stay active.
                         (= (loop-registry/spec-id-str spec-id)
                            (loop-registry/spec-id-str
                             (:tamaki.loop/spec-id campaign)))
                         ;; Legacy CLI ensure: retire other active campaigns on
                         ;; the same project (pre-registry supervisor behaviour).
                         (and (nil? (:tamaki.loop/spec-id campaign))
                              (= (:project options)
                                 (:tamaki.loop/project campaign)))))]
      (append-loop-event! campaign :loop/completed
                          {:reason :superseded-by-supervisor}))
    (if canonical
      (do (print-edn canonical) 0)
      (start-loop-with-options! options))))

(defn ensure-all-loops!
  "Discover enabled LoopSpecs and ensure one campaign per spec."
  [{:keys [options]}]
  (let [specs (->> (loop-registry/discover-specs
                    {:skip-invalid? (boolean (:skip-invalid options))})
                   (filter :loop/enabled)
                   vec)
        dry? (boolean (:dry-run options))]
    (when (empty? specs)
      (throw (ex-info "No enabled LoopSpecs discovered"
                      {:dirs (mapv str (loop-registry/default-search-dirs))})))
    (if dry?
      (do (print-edn
           (mapv (fn [spec]
                   (loop-registry/summarize-spec
                    spec
                    (last (sort-by :tamaki.loop/updated-at
                                   (filter #(and (= :active
                                                    (:tamaki.loop/status %))
                                                 (loop-registry/compatible-campaign?
                                                  spec %))
                                           (vals (campaigns)))))))
                 specs))
          0)
      (let [results
            (mapv
             (fn [spec]
               (let [out (java.io.StringWriter.)
                     _ (binding [*out* out]
                         (ensure-loop!
                          {:positional []
                           :options (loop-registry/ensure-options
                                     spec
                                     (select-keys options
                                                  [:auto-approve
                                                   :continuous
                                                   :runners
                                                   :interval-ms
                                                   :max-failures
                                                   :max-cycles
                                                   :project
                                                   :objective]))}))
                     text (str out)]
                 (try (edn/read-string text)
                      (catch Exception _
                        {:loop/id (:loop/id spec)
                         :error "ensure produced non-edn output"
                         :raw text}))))
             specs)]
        (print-edn results)
        0))))

(defn list-loops!
  "List discovered LoopSpecs joined with any matching active campaign."
  [{:keys [options]}]
  (let [specs (loop-registry/discover-specs
               {:skip-invalid? (boolean (:skip-invalid options))})
        active (filter #(= :active (:tamaki.loop/status %))
                       (vals (campaigns)))]
    (print-edn
     (mapv (fn [spec]
             (let [match (last (sort-by :tamaki.loop/updated-at
                                        (filter #(loop-registry/compatible-campaign?
                                                  spec %)
                                                active)))]
               (loop-registry/summarize-spec spec match)))
           specs))
    0))

(defn validate-loop-spec!
  [{:keys [positional options]}]
  (let [path (or (:spec options)
                 (first (filter #(and (string? %)
                                      (str/ends-with? % ".edn"))
                                positional)))]
    (when (str/blank? path)
      (throw (ex-info "Usage: tamaki loop validate SPEC.edn" {})))
    (print-edn (loop-registry/read-spec path))
    0))

(defn loop-status! [id]
  (if id
    (print-edn (or (campaign-by-id id)
                   (throw (ex-info "Unknown loop" {:loop-id id}))))
    (print-edn (->> (vals (campaigns))
                    (sort-by :tamaki.loop/updated-at >) vec)))
  0)

(defn stop-active-loops!
  "Durably complete active campaigns, optionally limited to one project.
  This is the safety boundary used when GitHub-governed self-evolution owns
  promotion and the legacy Radicle loop must not continue mutating code."
  [{:keys [options]}]
  (let [project (:project options)
        reason (keyword (or (:reason options) "operator-requested"))
        active (->> (vals (campaigns))
                    (filter #(= :active (:tamaki.loop/status %)))
                    (filter #(or (str/blank? project)
                                 (= project (:tamaki.loop/project %))))
                    vec)]
    (doseq [campaign active]
      (append-loop-event! campaign :loop/completed {:reason reason}))
    (print-edn {:stopped (mapv :tamaki.loop/id active)
                :project project
                :reason reason})
    0))

(defn latest-run-event [run-id kind]
  (last (filter #(and (= run-id (:tamaki.event/run %))
                      (= kind (:tamaki.event/kind %)))
                (events))))

(defn patch-commit-id [run-id patch-id]
  (some->> (events)
           (filter #(and (= run-id (:tamaki.event/run %))
                         (= :patch/created (:tamaki.event/kind %))
                         (= patch-id
                            (get-in % [:tamaki.event/data :patch/id]))))
           last
           :tamaki.event/data
           :commit/id))

(defn quality-snapshot []
  (let [kinds (frequencies (map :tamaki.event/kind (events)))]
    {:tests (get kinds :run/succeeded 0)
     :assertions (get kinds :patch/integrated 0)
     :failures (+ (get kinds :run/failed 0)
                  (get kinds :loop/cycle-failed 0))}))

(defn independent-review!
  [worker patch-id criteria]
  (let [commit-id (patch-commit-id (:agent.run/id worker) patch-id)
        reviewer (model/agent-run
                  {:goal (str "Independently review Radicle patch " patch-id
                              " at Git commit " commit-id
                              ". Verify every acceptance criterion and run the "
                              "documented tests. Inspect the commit with the "
                              "dedicated git diff/status tools or allowlisted "
                              "`git show --format=raw --no-patch " commit-id
                              "` and `git show --stat " commit-id
                              "`. Do not use git log, git branch, git merge-base, "
                              "compound shell commands, or bin/tamaki doctor. "
                              "After the documented tests pass, immediately "
                              "write the required verdict. The patch "
                              "id is not a Git object. Do not edit "
                              "tracked files, commit, deliver, or integrate.\n"
                              "Criteria:\n- " (str/join "\n- " criteria))
                   :project (:agent.run/project worker)
                   :mode :local :model (:agent.run/model worker)
                   :runner (:agent.run/runner worker)
                   :parent (:agent.run/id worker)
                   :capabilities #{:git :radicle}
                   :require-done-no-edit? true}
                  (now))
        verdict-file (io/file (:agent.run/project worker) ".tamaki" "reviews"
                              (str (:agent.run/id reviewer) ".edn"))
        verdict-path (str ".tamaki/reviews/" (.getName verdict-file))
        reviewer (update reviewer :agent.run/goal
                         str "\nUse the write_file tool with relative path "
                         (pr-str verdict-path)
                         " to write exactly one EDN verdict"
                         ": {:review/verdict :accepted|:rejected "
                         ":review/commit \"COMMIT\" :review/evidence [\"...\"]}. "
                         "Use :accepted only if every criterion passes.")]
    (.mkdirs (.getParentFile verdict-file))
    (store/append-event! (store/default-root)
                         (model/event reviewer :run/submitted (now)
                                      {:run reviewer}))
    (let [exit (execute-run! reviewer)
          completed (run-by-id (:agent.run/id reviewer))
          status (run-process! completed (delivery/git-status-command)
                               "independent review clean-tree check")
          verdict (when (.exists verdict-file)
                    (try (edn/read-string (slurp verdict-file))
                         (catch Exception _ nil)))]
      (when-not (and (zero? exit)
                     (empty? (delivery/porcelain-paths (:out status)))
                     (intelligence/valid-review-verdict? verdict commit-id))
        (throw (ex-info "Independent review rejected the patch"
                        {:review.run/id (:agent.run/id reviewer)
                         :exit exit
                         :verdict verdict
                         :expected-commit commit-id
                         :changed-paths
                         (delivery/porcelain-paths (:out status))})))
      (receipt! worker :review/independent
                {:patch/id patch-id :review.run/id (:agent.run/id reviewer)
                 :review/criteria criteria :review/verdict :accepted
                 :review/commit commit-id
                 :review/evidence (:review/evidence verdict)})
      (:agent.run/id reviewer))))

(defn tick-loop! [id]
  (let [campaign (or (campaign-by-id id)
                     (throw (ex-info "Unknown loop" {:loop-id id})))
        reason (agent-loop/stop-reason campaign (now))]
    (when reason
      (throw (ex-info "Loop cannot tick" {:loop-id id :reason reason})))
    (let [all-runs (remove nil? (vals (runs)))
          evaluation (kaizen/evaluate (events) all-runs (now))
          admission
          (kaizen/spawn-admission
           evaluation all-runs
           {:actor/capabilities #{:implementation :review}})]
      (when-not (:admitted? admission)
        (append-loop-event!
         campaign :loop/cycle-deferred
         {:reason (:reason admission)
          :admission admission
          :kaizen/decision (:kaizen/decision evaluation)})
        (throw (ex-info "Loop cycle deferred by global admission control"
                        {:loop-id id :admission admission}))))
    (let [cycle (inc (:tamaki.loop/cycles campaign))
          project (:tamaki.loop/project campaign)
          runner-id (agent-loop/runner-for-cycle campaign cycle)
          runner-profile (when runner-id (runners/profile runner-id))
          visual-observation (try
                               (visual/observe! project
                                                (store/default-root)
                                                (now))
                               (catch Exception error
                                 {:visual/status :analysis-failed
                                  :visual/error (.getMessage error)}))
          title (str "ASI cycle " cycle ": " (:tamaki.loop/objective campaign))]
      (append-loop-event! campaign :loop/cycle-started
                          {:loop/cycle cycle :runner runner-id})
      (append-loop-event! campaign :visual/observed
                          {:loop/cycle cycle :visual visual-observation})
      (try
        (let [status (delivery/succeeded!
                      (delivery/execute! (delivery/git-status-command) project)
                      "cycle clean-tree check")]
          (when (seq (delivery/porcelain-paths (:out status)))
            (throw (ex-info "Cycle requires a clean working tree"
                            {:paths (delivery/porcelain-paths (:out status))})))
          (let [listed (delivery/execute!
                        (delivery/issue-list-command) project)
                _ (when-not (zero? (:exit listed))
                    (let [auth-required?
                          (boolean
                           (re-find
                            #"(?i)(not registered|ssh-agent|passphrase|required to read your Radicle key)"
                            (str (:err listed) "\n" (:out listed))))]
                      (if auth-required?
                        (throw
                         (ex-info
                          "Radicle authority is not available to this process"
                          {:loop/deferred? true
                           :reason :radicle-auth-required}))
                        (delivery/succeeded! listed
                                             "cycle issue discovery"))))
                existing (intelligence/parse-issue-list (:out listed))
                operational-dynamics
                (intelligence/dynamics-signals
                 {:failures (:tamaki.loop/failures campaign)
                  :max-failures (:tamaki.loop/max-failures campaign)
                  :active-runs (count (filter #(= :running
                                                 (:agent.run/status %))
                                            (vals (runs))))
                  :open-issues (count existing)})
                business-state (business-summary)
                business-signals (business/control-signals business-state)
                work-title
                (if (= :unobserved (:business/status business-state))
                  (str "ASI cycle " cycle
                       ": establish an observed revenue KPI baseline")
                  title)
                dynamics
                (-> (merge intelligence/default-signals
                           business-signals operational-dynamics)
                    (assoc :urgency
                           (max (:urgency operational-dynamics)
                                (:urgency business-signals)))
                    (assoc :feedback-pressure
                           (max (:feedback-pressure operational-dynamics)
                                (:business-pressure business-signals))))
                candidates
                (->> existing
                     (mapv
                      (fn [candidate]
                        (let [shown (delivery/succeeded!
                                     (delivery/execute!
                                      (delivery/issue-show-command
                                       (:issue/id candidate))
                                      project)
                                     "candidate issue inspection")
                              metadata (intelligence/parse-issue-metadata
                                        (:out shown))]
                          (-> candidate
                              (merge metadata)
                              (update :issue/signals merge dynamics)))))
                     (filterv :issue/managed?))
                prior-belief
                (some->> (events)
                         (filter #(and (= id (:tamaki.event/run %))
                                       (= :inference/belief-updated
                                          (:tamaki.event/kind %))))
                         last :tamaki.event/data :belief)
                belief (active-inference/belief-state
                        prior-belief
                        (merge intelligence/default-signals dynamics))
                eligible (intelligence/rank candidates)
                selected-policy
                (active-inference/select-policy
                 belief
                 (mapv (fn [candidate]
                         {:id (:issue/id candidate)
                          :observations (:issue/signals candidate)})
                       eligible))
                selected-node
                (some #(when (= (:issue/id %)
                                (:policy/id selected-policy)) %)
                      eligible)
                selected
                (when selected-node
                  {:issue selected-node
                   :score (intelligence/leverage-score selected-node)
                   :blocked-count (- (count candidates) (count eligible))
                   :candidate-count (count candidates)
                   :ranking (mapv :issue/id eligible)
                   :inference selected-policy})
                _ (append-loop-event!
                   campaign :inference/belief-updated
                   {:loop/cycle cycle :belief belief
                    :policy selected-policy})
                criteria (or (seq (get-in selected [:issue :issue/criteria]))
                             (intelligence/acceptance-criteria
                              (:tamaki.loop/objective campaign)))
                opened (when-not selected
                         (delivery/succeeded!
                          (delivery/execute!
                           (delivery/issue-create-command
                            work-title
                            (str "Managed by: tamaki-supervisor\n\n"
                                 (agent-loop/cycle-goal campaign cycle "<pending>")
                                 "\n\nAcceptance: "
                                 (str/join "\nAcceptance: " criteria)))
                           project)
                          "cycle issue creation"))
                issue-id (or (get-in selected [:issue :issue/id])
                             (delivery/output-id opened))
                decision (or selected
                             {:issue (intelligence/issue-node
                                      {:id issue-id :title work-title
                                       :criteria criteria :signals dynamics})
                              :score (intelligence/leverage-score
                                      (intelligence/issue-node
                                       {:id issue-id :title work-title
                                        :criteria criteria :signals dynamics}))
                              :candidate-count 1 :blocked-count 0})
                execution-project
                (runners/prepare-worktree!
                 project
                 (str "loop-" id "-" cycle)
                 (or runner-id "default"))
                run (model/agent-run
                     {:goal (str (agent-loop/cycle-goal campaign cycle issue-id)
                                 "\nAcceptance criteria:\n- "
                                 (str/join "\n- " criteria)
                                 "\nBlockers: "
                                 (pr-str (get-in decision
                                                 [:issue :issue/blockers]))
                                 "\nVisual observation: "
                                 (pr-str
                                  (select-keys visual-observation
                                               [:visual/status :visual/path
                                                :visual/findings
                                                :visual/suggested-issue])))
                      :project execution-project
                      :source-project project
                      :mode :local
                      :model (or (:model runner-profile)
                                 (:tamaki.loop/model campaign))
                      :runner runner-id
                      :organism (or (:tamaki.loop/ao campaign)
                                    (:tamaki.loop/organism campaign))
                      :capabilities #{:git :radicle}}
                     (now))]
            (store/append-event! (store/default-root)
                                 (model/event run :run/submitted (now) {:run run}))
            (receipt! run :issue/discovered {:issue/id issue-id
                                             :loop/id id :loop/cycle cycle})
            (receipt! run :issue/prioritized
                      {:issue/id issue-id
                       :issue/selection decision
                       :issue/dynamics dynamics
                       :business/control
                       (select-keys business-state
                                    [:business/status :business/kpis
                                     :business/progress
                                     :business/control-score])
                       :issue/criteria criteria})
            (let [before (quality-snapshot)]
            (let [exit (execute-run! run)
                  completed (run-by-id (:agent.run/id run))]
              (when-not (zero? exit)
                (run-process! completed (delivery/issue-close-command issue-id)
                              "failed-cycle issue closure")
                (throw (ex-info "Cycle AgentRun failed"
                                {:run-id (:agent.run/id run) :exit exit})))
              (let [changed-result (run-process!
                                    completed (delivery/git-status-command)
                                    "cycle changed-path discovery")
                    paths (delivery/porcelain-paths (:out changed-result))]
                (if (empty? paths)
                  (do
                    (run-process! completed (delivery/issue-solve-command issue-id)
                                  "no-change issue resolution")
                    (append-loop-event!
                     campaign :loop/cycle-no-change
                     {:loop/cycle cycle :issue/id issue-id
                      :agent.run/id (:agent.run/id run)})
                    (print-edn {:loop/id id :loop/cycle cycle
                                :result :no-change :issue/id issue-id}))
                  (do
                    (deliver! {:positional [(:agent.run/id run)]
                               :options {:issue issue-id
                                         :paths (str/join "," paths)
                                         :message work-title}})
                    (let [patch-id (or
                                    (get-in
                                     (latest-run-event (:agent.run/id run)
                                                       :patch/created)
                                     [:tamaki.event/data :patch/id])
                                    (throw
                                     (ex-info "Cycle delivery did not produce a patch id"
                                              {:run-id (:agent.run/id run)})))
                          evidence "Tamaki cycle agent completed documented test gate"]
                      (review! {:positional [patch-id]
                                :options {:run (:agent.run/id run)
                                          :tests evidence}})
                      (let [review-run-id
                            (independent-review! completed patch-id criteria)]
                        (receipt! completed :effect/measured
                                  (merge {:patch/id patch-id
                                          :review.run/id review-run-id
                                          :effect/before before}
                                         (let [after (quality-snapshot)]
                                           (assoc (intelligence/effect before after)
                                                  :effect/after after)))))
                      (let [decision
                            (if (:tamaki.loop/auto-approve campaign)
                              :approved
                              (:decision
                               (supervisor/consult!
                                {:title "Tamaki: integration confirmation"
                                 :summary
                                 (str "Issue " issue-id
                                      " passed implementation and independent review.")
                                 :action (str "Integrate patch " patch-id)
                                 :impact
                                 "Updates the repository and resolves the Radicle issue."
                                 :voice? true})))]
                        (if (= :approved decision)
                          (do
                            (integrate! {:positional [patch-id]
                                         :options {:run (:agent.run/id run)
                                                   :issue issue-id
                                                   :tests evidence
                                                   :approve true}})
                            (append-loop-event!
                             campaign :loop/cycle-integrated
                             {:loop/cycle cycle :issue/id issue-id
                              :patch/id patch-id
                              :hil/decision decision
                              :agent.run/id (:agent.run/id run)}))
                          (append-loop-event!
                           campaign :loop/cycle-reviewed
                           {:loop/cycle cycle :issue/id issue-id
                            :patch/id patch-id
                            :hil/decision decision
                            :agent.run/id (:agent.run/id run)}))
                        (print-edn
                         {:loop/id id :loop/cycle cycle
                          :result (if (= :approved decision)
                                    :integrated :awaiting-approval)
                          :hil/decision decision
                          :issue/id issue-id :patch/id patch-id})))))))))
            )
        (catch Exception e
          (let [deferred? (:loop/deferred? (ex-data e))]
            (append-loop-event!
             campaign
             (if deferred? :loop/cycle-deferred :loop/cycle-failed)
             (cond-> {:loop/cycle cycle
                      :error (.getMessage e)
                      :error/data (ex-data e)}
               deferred? (assoc :reason (:reason (ex-data e))))))
          (throw e))))
    0))

(defn run-loop! [id]
  (loop []
    (let [campaign (or (campaign-by-id id)
                       (throw (ex-info "Unknown loop" {:loop-id id})))
          reason (agent-loop/stop-reason campaign (now))]
      (if reason
        (do
          (when (= :active (:tamaki.loop/status campaign))
            (append-loop-event! campaign :loop/completed {:reason reason}))
          (print-edn {:loop/id id :status :completed :reason reason})
          0)
        (do
          (try
            (tick-loop! id)
            (catch Exception e
              (binding [*out* *err*]
                (println "tamaki loop cycle failed:" (.getMessage e)))))
          (let [next-campaign (campaign-by-id id)]
            (when-not (agent-loop/stop-reason next-campaign (now))
              (Thread/sleep (:tamaki.loop/interval-ms next-campaign))))
          (recur))))))

(defn loop! [{:keys [positional] :as parsed}]
  (let [[action id] positional]
    (case action
      "start" (start-loop! parsed)
      "ensure" (ensure-loop! parsed)
      "ensure-all" (ensure-all-loops! parsed)
      "list" (list-loops! parsed)
      "validate" (validate-loop-spec! parsed)
      "status" (loop-status! id)
      "stop-active" (stop-active-loops! parsed)
      "tick" (tick-loop! id)
      "run" (run-loop! id)
      (throw (ex-info
              "Usage: tamaki loop start|ensure|ensure-all|list|validate|status|stop-active|tick|run ..."
              {})))))

(defn- ensure-fleet-loop! [path]
  (let [spec (loop-registry/read-spec path)
        output (java.io.StringWriter.)]
    (binding [*out* output]
      (ensure-loop!
       {:positional []
        :options (loop-registry/ensure-options spec)}))
    (edn/read-string (str output))))

(defn- append-fleet-event! [kind data]
  (store/append-event!
   (store/default-root)
   {:tamaki.event/version 1
    :tamaki.event/id (str (random-uuid))
    :tamaki.event/run "fleet::etzhayyim"
    :tamaki.event/parent nil
    :tamaki.event/kind kind
    :tamaki.event/at (now)
    :tamaki.event/data data}))

(defn- ensure-fleet-repository! [candidate]
  (let [project (:ao/project candidate)
        checkout (io/file project)
        url (str "https://github.com/" (:ao/repository candidate) ".git")
        rid (get-in candidate [:ao/signals :radicle-id])]
    ;; A linked worktree has a `.git` file instead of a directory; it is still
    ;; a complete checkout and must never be cloned over.
    (when-not (.exists (io/file checkout ".git"))
      (.mkdirs (.getParentFile checkout))
      (delivery/succeeded!
       (delivery/execute! ["git" "clone" "--no-single-branch" url project])
       (str "AO checkout hydration " (:ao/id candidate))))
    (let [existing (delivery/execute! ["git" "remote" "get-url" "rad"]
                                      project)]
      (when-not (zero? (:exit existing))
        (when (str/blank? rid)
          (throw (ex-info "AO has no Radicle identity"
                          {:ao/id (:ao/id candidate)})))
        (let [fetch-url (str/replace rid #"^rad:" "rad://")
              node (-> (delivery/succeeded!
                        (delivery/execute! ["rad" "self" "--nid"] project)
                        "Radicle node identity")
                       :out str/trim)]
          (delivery/succeeded!
           (delivery/execute! ["git" "remote" "add" "rad" fetch-url]
                              project)
           "Radicle remote registration")
          (delivery/succeeded!
           (delivery/execute!
            ["git" "remote" "set-url" "--push" "rad"
             (str fetch-url "/" node)]
            project)
           "Radicle push authority registration"))))
    candidate))

(defn fleet!
  [{:keys [positional options]}]
  (let [[action] positional
        root (store/default-root)
        policy-path (or (:policy options)
                        "organisms/etzhayyim-fleet.edn")]
    (case action
      "status"
      (do
        (print-edn
         (if-let [state (ao-fleet/read-state root)]
           (ao-fleet/public-summary state)
           {:ao.fleet/status :unobserved
            :ao.fleet/policy policy-path}))
        0)

      "reconcile"
      (let [registry
            (or (family/read-registry root)
                (throw (ex-info
                        "AO fleet requires a reconciled family registry"
                        {:run "tamaki family sync --execute"})))
            policy (ao-fleet/read-policy policy-path)
            previous (ao-fleet/read-state root)
            workspace (or (System/getenv "TAMAKI_WORKSPACE")
                          (System/getenv "COM_JUNKAWASAKI_ROOT")
                          (System/getProperty "user.dir"))
            at (now)
            projection
            (ao-fleet/projection policy registry workspace previous at)]
        (if-not (:execute options)
          (do
            (print-edn
             (assoc (ao-fleet/public-summary projection)
                    :ao.fleet/executed? false))
            0)
          (let [_hydrated
                (doseq [candidate (:ao.fleet/selected projection)]
                  (ensure-fleet-repository! candidate))
                paths
                (ao-fleet/write-loop-specs!
                 root policy (:ao.fleet/selected projection))
                selected-specs
                (mapv loop-registry/read-spec paths)
                active-campaigns
                (->> (vals (campaigns))
                     (filter #(= :active (:tamaki.loop/status %)))
                     vec)
                ;; Fleet reconciliation is frequent while the append-only
                ;; event stream can be large. Reuse the single campaign fold
                ;; for already-registered AOs instead of re-reading the whole
                ;; stream once per selected LoopSpec.
                ensured-campaigns
                (mapv
                 (fn [path spec]
                   (or (last
                        (sort-by
                         :tamaki.loop/updated-at
                         (filter #(loop-registry/compatible-campaign? spec %)
                                 active-campaigns)))
                       (ensure-fleet-loop! path)))
                 paths selected-specs)
                selected-ids
                (set (map #(loop-registry/spec-id-str (:loop/id %))
                          selected-specs))
                _ (doseq [campaign (vals (campaigns))
                          :let [spec-id (:tamaki.loop/spec-id campaign)]
                          :when (and (= :active (:tamaki.loop/status campaign))
                                     (str/starts-with? (str spec-id) "ao/")
                                     (not (contains? selected-ids
                                                     (str spec-id))))]
                    (append-loop-event!
                     campaign :loop/completed
                     {:reason :ao-fleet-deactivated}))
                dispatch-ids (set (map :ao/id (:ao.fleet/dispatch projection)))
                dispatch-campaigns
                (filterv #(contains? dispatch-ids (:tamaki.loop/ao %))
                         ensured-campaigns)
                results
                (mapv
                 (fn [campaign]
                   (try
                     (tick-loop! (:tamaki.loop/id campaign))
                     {:ao/id (:tamaki.loop/ao campaign)
                      :loop/id (:tamaki.loop/id campaign)
                      :status :completed}
                     (catch Exception error
                       {:ao/id (:tamaki.loop/ao campaign)
                        :loop/id (:tamaki.loop/id campaign)
                        :status :deferred
                        :reason (.getMessage error)
                        :data (ex-data error)})))
                 dispatch-campaigns)
                attempted-at
                (reduce (fn [m {:keys [ao/id]}]
                          (assoc m id at))
                        (:ao.fleet/last-dispatched-at projection)
                        results)
                state (assoc projection
                             :ao.fleet/last-dispatched-at attempted-at
                             :ao.fleet/results results)]
            (ao-fleet/write-state! root state)
            (append-fleet-event!
             :ao.fleet/reconciled
             (assoc (ao-fleet/public-summary state)
                    :ao.fleet/results results))
            (print-edn
             (assoc (ao-fleet/public-summary state)
                    :ao.fleet/results results
                    :ao.fleet/executed? true))
            0)))

      (throw
       (ex-info
        "Usage: tamaki fleet status|reconcile [--policy FLEET.edn] [--execute]"
        {})))))

(defn bridge!
  [{:keys [positional options]}]
  (let [action (first positional)
        candidates (evolution/candidates (events))
        plan (bridge/plan candidates)]
    (case action
      "status" (do (print-edn {:bridge/actor :bridge/radicle-github
                                :bridge/authority :radicle
                                :bridge/gaps plan})
                   0)
      "reconcile"
      (if-not (:execute options)
        (do (print-edn {:bridge/actor :bridge/radicle-github
                        :bridge/dry-run true :bridge/gaps plan})
            0)
        (let [results
              (mapv
               (fn [{:bridge/keys [action candidate pr-url] :as gap}]
                 (let [candidate-state (get candidates candidate)]
                   (case action
                     :open-draft-pr
                     (do
                       (evolve-open-pr!
                        {:positional ["open-pr" candidate]
                         :options {:title
                                   (str "mirror: "
                                        (:evolution/objective candidate-state))}})
                       (assoc gap :bridge/result :opened))
                     :observe-github
                     (let [result
                           (delivery/execute!
                            ["gh" "pr" "view" pr-url
                             "--json" "state,statusCheckRollup,reviews"]
                            (:evolution/project candidate-state))
                           evidence (bridge/github-observation result (now))]
                       (append-evolution! candidate-state :evolution/evidence
                                          {:evidence evidence})
                       (assoc gap :bridge/result
                              (if (zero? (:exit result))
                                :observed :observation-failed)))
                     gap)))
               plan)]
          (store/append-event!
           (store/default-root)
           {:tamaki.event/version 1
            :tamaki.event/id (str (random-uuid))
            :tamaki.event/run "bridge::radicle-github"
            :tamaki.event/parent nil
            :tamaki.event/kind :bridge/reconciled
            :tamaki.event/at (now)
            :tamaki.event/data {:bridge/actor :bridge/radicle-github
                                :bridge/gaps (count plan)
                                :bridge/results results}})
          (print-edn {:bridge/actor :bridge/radicle-github
                      :bridge/reconciled results})
          (if (every? #(not= :observation-failed (:bridge/result %))
                      results)
            0 1)))
      (throw (ex-info "Usage: tamaki bridge status|reconcile [--execute]" {})))))

(defn dispatch
  [args]
  (let [[command & rest] args
        parsed (parse-args rest)]
    (case command
      "submit" (or (submit! parsed) 0)
      "exec" (exec! parsed)
      "run" (execute-run! (run-by-id (first (:positional parsed))))
      "status" (do (status! (first (:positional parsed))) 0)
      "resume" (resume! (first (:positional parsed)))
      "cancel" (cancel! (first (:positional parsed))
                         (get-in parsed [:options :reason]))
      "agents" (do (agents! (first (:positional parsed))) 0)
      "runners" (do (runners!) 0)
      "swarm" (swarm! parsed)
      "issue" (issue! parsed)
      "work" (if (= "issue" (first (:positional parsed)))
                 (work-issue! parsed)
                 (throw (ex-info "Usage: tamaki work issue ISSUE-ID" {})))
      "deliver" (deliver! parsed)
      "review" (review! parsed)
      "integrate" (integrate! parsed)
      "result" (result! parsed)
      "loop" (loop! parsed)
      "consult" (consult! parsed)
      "mail" (mail! parsed)
      "voice" (or (voice! parsed) 0)
      "actor" (actor! parsed)
      "capability" (capability! parsed)
      "kpi" (kpi! parsed)
      "service" (service! parsed)
      "content" (content! parsed)
      "finance" (finance! parsed)
      "homeostasis" (homeostasis! parsed)
      "memory" (memory! parsed)
      "store" (store! parsed)
      "storage" (storage! parsed)
      "maintenance" (maintenance! parsed)
      "family" (family! parsed)
      "fleet" (fleet! parsed)
      "topology" (topology! parsed)
      "evolve" (evolve! parsed)
      "bridge" (bridge! parsed)
      "nodes" (passthrough! (adapters/fleet-tool-command "nodes" rest)
                            (adapters/sibling "kotoba-fleet"))
      "tick" (passthrough! (adapters/fleet-tool-command "tick" rest)
                           (adapters/sibling "kotoba-fleet"))
      "infer" (passthrough! (adapters/murakumo-command :infer rest)
                            (adapters/sibling "murakumo"))
      "murakumo" (passthrough! (adapters/murakumo-command :core rest)
                               (adapters/sibling "murakumo"))
      "doctor" (doctor!)
      "contract" (do (print-edn {:version model/contract-version
                                  :transitions model/transitions})
                     0)
      (do (println (usage)) (if (or (nil? command) (= command "help")) 0 2)))))

(defn -main [& args]
  (try
    (let [exit (dispatch args)]
      (when (and (number? exit) (not (zero? exit)))
        (System/exit exit)))
    (catch Exception e
      (binding [*out* *err*]
        (println "tamaki:" (.getMessage e))
        (when-let [data (ex-data e)] (print-edn data)))
      (System/exit 2))))
