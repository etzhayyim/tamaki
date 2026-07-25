(ns kotoba.tamaki.cli
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [kotoba.tamaki.adapters :as adapters]
            [kotoba.tamaki.delivery :as delivery]
            [kotoba.tamaki.loop :as agent-loop]
            [kotoba.tamaki.intelligence :as intelligence]
            [kotoba.tamaki.model :as model]
            [kotoba.tamaki.store :as store])
  (:gen-class))

(defn now [] (System/currentTimeMillis))

(defn usage []
  (str "tamaki — one CLI for Kotoba agent execution\n\n"
       "Usage:\n"
       "  tamaki submit <goal> --project PATH [--mode local|fleet] [options]\n"
       "  tamaki run <run-id>\n"
       "  tamaki status [run-id]\n"
       "  tamaki resume <run-id>\n"
       "  tamaki agents [run-id]\n"
       "  tamaki issue create|show ...\n"
       "  tamaki work issue <issue-id> --project PATH [--execute]\n"
       "  tamaki deliver <run-id> --issue ID --paths a,b --message TEXT\n"
       "  tamaki review <patch-id> --run RUN-ID --tests EVIDENCE\n"
       "  tamaki integrate <patch-id> --run RUN-ID --issue ID --tests EVIDENCE --approve\n"
       "  tamaki loop start|status|tick|run ...\n"
       "  tamaki nodes [fleet-nodes options]\n"
       "  tamaki tick [fleet-tick options]\n"
       "  tamaki infer <probe|plan|up|down|ps|serve|generate> ...\n"
       "  tamaki murakumo <command> ...\n"
       "  tamaki doctor\n"
       "  tamaki contract\n\n"
       "Options:\n"
       "  --repo OWNER/REPO --pin SHA --node NAME|auto --model MODEL\n"
       "  --requires git,nbb,clojure --parent RUN-ID --execute\n"))

(defn parse-args
  [args]
  (loop [xs args positional [] options {}]
    (if (empty? xs)
      {:positional positional :options options}
      (let [[x y & more] xs]
        (if (str/starts-with? x "--")
          (if (contains? #{"--execute" "--approve" "--auto-approve"} x)
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

(declare execute-run!)

(defn submit!
  [{:keys [positional options]}]
  (let [goal (first positional)
        mode (keyword (or (:mode options) "local"))
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
              :model (:model options)
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
        mode (:agent.run/mode run)]
    (when-not (adapters/ready-for? mode report)
      (throw (ex-info "Runtime is not ready" {:mode mode :doctor report})))
    (let [leased (model/transition run :leased (now)
                                   {:agent.run/worker
                                    (or (System/getenv "TAMAKI_WORKER_ID")
                                        (.getHostName (java.net.InetAddress/getLocalHost)))})
          _ (emit! run :run/leased
                   (select-keys leased [:agent.run/worker :agent.run/node]))
          argv (if (= mode :fleet)
                 (adapters/fleet-command leased (write-work! leased))
                 (adapters/local-command leased))
          _ (emit! leased :run/started {:agent.run/command argv})
          exit (binding [adapters/*process-env*
                         {"KC_LOOP_ID" (:agent.run/id run)
                          "KC_SESSION" (:agent.run/id run)}]
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

(defn start-loop! [{:keys [options]}]
  (let [campaign (agent-loop/campaign
                  {:objective (:objective options)
                   :project (:project options)
                   :model (:model options)
                   :max-cycles (parse-long-option options :max-cycles 10)
                   :interval-ms (parse-long-option options :interval-ms 60000)
                   :max-failures (parse-long-option options :max-failures 3)
                   :auto-approve (boolean (:auto-approve options))}
                  (now))]
    (append-loop-event! campaign :loop/started {:campaign campaign})
    (print-edn campaign)
    0))

(defn loop-status! [id]
  (if id
    (print-edn (or (campaign-by-id id)
                   (throw (ex-info "Unknown loop" {:loop-id id}))))
    (print-edn (->> (vals (campaigns))
                    (sort-by :tamaki.loop/updated-at >) vec)))
  0)

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
                              "documented tests. Inspect `git show " commit-id
                              "`; the patch id is not a Git object. Do not edit "
                              "tracked files, commit, deliver, or integrate.\n"
                              "Criteria:\n- " (str/join "\n- " criteria))
                   :project (:agent.run/project worker)
                   :mode :local :model (:agent.run/model worker)
                   :parent (:agent.run/id worker)
                   :capabilities #{:git :radicle}}
                  (now))
        verdict-file (io/file (:agent.run/project worker) ".tamaki" "reviews"
                              (str (:agent.run/id reviewer) ".edn"))
        reviewer (update reviewer :agent.run/goal
                         str "\nWrite exactly one EDN verdict to "
                         (.getAbsolutePath verdict-file)
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
        reason (agent-loop/stop-reason campaign)]
    (when reason
      (throw (ex-info "Loop cannot tick" {:loop-id id :reason reason})))
    (let [cycle (inc (:tamaki.loop/cycles campaign))
          project (:tamaki.loop/project campaign)
          title (str "ASI cycle " cycle ": " (:tamaki.loop/objective campaign))]
      (append-loop-event! campaign :loop/cycle-started {:loop/cycle cycle})
      (try
        (let [status (delivery/succeeded!
                      (delivery/execute! (delivery/git-status-command) project)
                      "cycle clean-tree check")]
          (when (seq (delivery/porcelain-paths (:out status)))
            (throw (ex-info "Cycle requires a clean working tree"
                            {:paths (delivery/porcelain-paths (:out status))})))
          (let [listed (delivery/succeeded!
                        (delivery/execute! (delivery/issue-list-command) project)
                        "cycle issue discovery")
                existing (intelligence/parse-issue-list (:out listed))
                dynamics (intelligence/dynamics-signals
                          {:failures (:tamaki.loop/failures campaign)
                           :max-failures (:tamaki.loop/max-failures campaign)
                           :active-runs (count (filter #(= :running
                                                          (:agent.run/status %))
                                                     (vals (runs))))
                           :open-issues (count existing)})
                candidates
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
                         (update :issue/signals merge dynamics))))
                 existing)
                selected (intelligence/selection candidates)
                criteria (or (seq (get-in selected [:issue :issue/criteria]))
                             (intelligence/acceptance-criteria
                              (:tamaki.loop/objective campaign)))
                opened (when-not selected
                         (delivery/succeeded!
                          (delivery/execute!
                           (delivery/issue-create-command
                            title
                            (str (agent-loop/cycle-goal campaign cycle "<pending>")
                                 "\n\nAcceptance: "
                                 (str/join "\nAcceptance: " criteria)))
                           project)
                          "cycle issue creation"))
                issue-id (or (get-in selected [:issue :issue/id])
                             (delivery/output-id opened))
                decision (or selected
                             {:issue (intelligence/issue-node
                                      {:id issue-id :title title
                                       :criteria criteria :signals dynamics})
                              :score (intelligence/leverage-score
                                      (intelligence/issue-node
                                       {:id issue-id :title title
                                        :criteria criteria :signals dynamics}))
                              :candidate-count 1 :blocked-count 0})
                run (model/agent-run
                     {:goal (str (agent-loop/cycle-goal campaign cycle issue-id)
                                 "\nAcceptance criteria:\n- "
                                 (str/join "\n- " criteria)
                                 "\nBlockers: "
                                 (pr-str (get-in decision
                                                 [:issue :issue/blockers])))
                      :project project :mode :local
                      :model (:tamaki.loop/model campaign)
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
                                         :message title}})
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
                      (if (:tamaki.loop/auto-approve campaign)
                        (do
                          (integrate! {:positional [patch-id]
                                       :options {:run (:agent.run/id run)
                                                 :issue issue-id
                                                 :tests evidence
                                                 :approve true}})
                          (append-loop-event!
                           campaign :loop/cycle-integrated
                           {:loop/cycle cycle :issue/id issue-id
                            :patch/id patch-id :agent.run/id (:agent.run/id run)}))
                        (append-loop-event!
                         campaign :loop/cycle-reviewed
                         {:loop/cycle cycle :issue/id issue-id
                          :patch/id patch-id :agent.run/id (:agent.run/id run)}))
                      (print-edn {:loop/id id :loop/cycle cycle
                                  :result (if (:tamaki.loop/auto-approve campaign)
                                            :integrated :awaiting-approval)
                                  :issue/id issue-id :patch/id patch-id}))))))))
            )
        (catch Exception e
          (append-loop-event! campaign :loop/cycle-failed
                              {:loop/cycle cycle :error (.getMessage e)
                               :error/data (ex-data e)})
          (throw e))))
    0))

(defn run-loop! [id]
  (loop []
    (let [campaign (or (campaign-by-id id)
                       (throw (ex-info "Unknown loop" {:loop-id id})))
          reason (agent-loop/stop-reason campaign)]
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
            (when-not (agent-loop/stop-reason next-campaign)
              (Thread/sleep (:tamaki.loop/interval-ms next-campaign))))
          (recur))))))

(defn loop! [{:keys [positional] :as parsed}]
  (let [[action id] positional]
    (case action
      "start" (start-loop! parsed)
      "status" (loop-status! id)
      "tick" (tick-loop! id)
      "run" (run-loop! id)
      (throw (ex-info "Usage: tamaki loop start|status|tick|run ..." {})))))

(defn dispatch
  [args]
  (let [[command & rest] args
        parsed (parse-args rest)]
    (case command
      "submit" (or (submit! parsed) 0)
      "run" (execute-run! (run-by-id (first (:positional parsed))))
      "status" (do (status! (first (:positional parsed))) 0)
      "resume" (resume! (first (:positional parsed)))
      "agents" (do (agents! (first (:positional parsed))) 0)
      "issue" (issue! parsed)
      "work" (if (= "issue" (first (:positional parsed)))
                 (work-issue! parsed)
                 (throw (ex-info "Usage: tamaki work issue ISSUE-ID" {})))
      "deliver" (deliver! parsed)
      "review" (review! parsed)
      "integrate" (integrate! parsed)
      "loop" (loop! parsed)
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
