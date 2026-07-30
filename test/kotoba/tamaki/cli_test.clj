(ns kotoba.tamaki.cli-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.adapters :as adapters]
            [kotoba.tamaki.cli :as cli]
            [kotoba.tamaki.delivery :as delivery]
            [kotoba.tamaki.model :as model]
            [kotoba.tamaki.store :as store]
            [kotoba.tamaki.supervisor :as supervisor]
            [kotoba.tamaki.workplace :as workplace]))

(def ready-report
  {:bb {:ok? true}
   :kotoba-code {:ok? true}})

(defn temp-root []
  (str (java.nio.file.Files/createTempDirectory
        "tamaki-cli-test-"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn call [args]
  (let [out (java.io.StringWriter.)]
    {:exit (binding [*out* out]
             (cli/dispatch args))
     :value (edn/read-string (str out))}))

(defn event-kinds [root]
  (mapv :tamaki.event/kind (store/read-local-events root)))

(deftest result-evaluator-receives-only-one-targets-bounded-evidence
  (let [patch "patch-1"
        context
        (cli/result-evaluation-target-context
         {:kaizen/evidence
          {:evaluation-debt [(str "result/" patch) "result/patch-2"]}}
         [{:tamaki.event/id "review-1"
           :tamaki.event/run "run-1"
           :tamaki.event/kind :review/independent
           :tamaki.event/at 10
           :tamaki.event/data
           {:patch/id patch
            :review/verdict :accepted
            :review/evidence ["tests passed"]
            :private/body "must not enter the prompt"}}
          {:tamaki.event/id "other"
           :tamaki.event/kind :patch/integrated
           :tamaki.event/data {:patch/id "patch-2"}}
          {:tamaki.event/id "noise"
           :tamaki.event/kind :agent/activity
           :tamaki.event/data {:patch/id patch}}])]
    (is (= "result/patch-1" (:evaluation/result context)))
    (is (= ["review-1"]
           (mapv :tamaki.event/id
                 (:evaluation/evidence-events context))))
    (is (nil?
         (get-in context
                 [:evaluation/evidence-events 0
                  :tamaki.event/data :private/body])))))

(deftest consultation-speaks-only-at-the-decision-boundary
  (let [requests (atom [])]
    (with-redefs [supervisor/consult!
                  (fn [request]
                    (swap! requests conj request)
                    {:run-id "supervisor" :decision :rejected})]
      (is (zero? (:exit (call ["consult" "Integrate reviewed patch"]))))
      (is (true? (:voice? (first @requests))))
      (is (zero? (:exit
                  (call ["consult" "CI confirmation" "--silent"]))))
      (is (false? (:voice? (second @requests)))))))

(deftest mail-review-displays-private-content-but-records-only-a-digest
  (let [draft-file (java.io.File/createTempFile "tamaki-mail-draft-" ".edn")
        request (atom nil)
        draft {:org :private-example
               :account :support
               :action :mail/send
               :recipients ["private@example.test"]
               :subject "Private subject"
               :body "PRIVATE BODY MUST NOT ENTER THE EVENT"
               :attachments [{:name "private.pdf"
                              :digest "sha256:file"
                              :size 10}]}]
    (spit draft-file (pr-str draft))
    (with-redefs [supervisor/consult-private!
                  (fn [value]
                    (reset! request value)
                    {:run-id "human-review" :decision :approved})]
      (let [{:keys [exit value]}
            (call ["mail" "review" "--file" (.getPath draft-file)])]
        (is (zero? exit))
        (is (= :approved (:mail.approval/status value)))
        (is (.contains (get-in @request [:display-request :summary])
                       "PRIVATE BODY MUST NOT ENTER THE EVENT"))
        (is (not (.contains (pr-str (:record-request @request))
                            "PRIVATE BODY MUST NOT ENTER THE EVENT")))
        (is (.contains (get-in @request [:record-request :impact])
                       "draft-digest"))))))

(deftest capability-cli-emits-a-minimal-kototama-envelope
  (let [actor-file (java.io.File/createTempFile
                    "tamaki-kototama-actor-" ".edn")
        spec
        {:actor/id :control/heartbeat
         :actor/project "/tmp/project"
         :actor/objective "bounded heartbeat"
         :actor/capabilities #{:organism/heartbeat}
         :actor/execution
         {:execution/substrate :kototama-wasm
          :execution/role :control-guest
          :execution/realizes #{:organism/heartbeat}
          :execution/capability-contract
          {:contract/version 1
           :abi/namespace "actor:host" :abi/version 0
           :imports #{:clock-monotonic :sha256-hex :log-write}
           :grants #{:clock-monotonic :sha256-hex :log-write}
           :limits {:allow-write-imports? true
                    :allow-secret-imports? false
                    :max-http-posts 0 :max-http-fetches 0
                    :max-llm-infers 0 :allowed-url-prefixes []}
           :effect-policy {:clock :autonomous :crypto :autonomous
                           :storage-write :autonomous}}}
         :actor/hil-policy {:external-effect :approval-required}
         :actor/scale {:min 0 :desired 0 :max 1}
         :actor/runners [{:runner :kototama :weight 1}]}]
    (spit actor-file (pr-str spec))
    (let [{:keys [exit value]}
          (call ["capability" "envelope" (.getPath actor-file)])]
      (is (zero? exit))
      (is (= 1 (:tamaki.capability/version value)))
      (is (= ":control/heartbeat" (:tamaki.capability/actor value)))
      (is (= #{:clock-monotonic :sha256-hex :log-write}
             (:tamaki.capability/imports value)))
      (is (nil? (:actor/objective value)))
      (is (nil? (:actor/capabilities value))))))

(deftest business-feedback-enters-the-actor-decision-context
  (let [spec {:actor/objective "Grow verified value"}
        feedback #:business{:status :observed
                            :kpis {:traffic 1000 :revenue-jpy 0}
                            :progress {:conversion 0.0}
                            :control-score 0.1}
        objective (:actor/objective
                   (cli/attach-business-feedback spec feedback))]
    (is (.contains objective "Latest durable business control evidence"))
    (is (.contains objective ":traffic 1000"))
    (is (.contains objective ":revenue-jpy 0"))
    (is (.contains objective "do not interpret traffic as revenue"))))

(deftest business-kpi-observation-is-durable-and-queryable
  (let [root (temp-root)
        observation (java.io.File/createTempFile "tamaki-kpi-" ".edn")
        targets (java.io.File/createTempFile "tamaki-targets-" ".edn")]
    (spit observation
          (pr-str {:period-days 7
                   :stocks {:mrr-jpy 500000 :qualified-leads 20}
                   :flows {:delta-mrr-jpy 100000
                           :experiments-shipped 2}
                   :rates {:confidence 0.8}}))
    (spit targets
          (pr-str {:target/mrr-jpy 1000000
                   :target/risk-adjusted-delta-mrr-jpy 100000
                   :target/experiments-per-week 3
                   :target/activation-rate 0.3
                   :target/paid-conversion-rate 0.1
                   :target/max-churn-rate 0.05}))
    (with-redefs [store/default-root (constantly root)
                  store/backend (constantly :file)
                  cli/now (constantly 1000)]
      (let [{observed :value}
            (call ["kpi" "observe" "--file" (.getPath observation)
                   "--targets" (.getPath targets)])
            {status :value}
            (call ["kpi" "status" "--targets" (.getPath targets)])]
        (is (= :observed (:business/status observed)))
        (is (= observed status))
        (is (= [:business/observed] (event-kinds root)))))))

(deftest supervisor-ensure-reuses-one-compatible-loop
  (let [root (temp-root)
        args ["loop" "ensure"
              "--project" "/tmp/project"
              "--objective" "operate continuously"
              "--organism-name" "Hikari"
              "--runners" "codex,claude"
              "--continuous"]]
    (with-redefs [store/default-root (constantly root)
                  store/backend (constantly :file)
                  cli/now (constantly 1000)]
      (let [first-id (:tamaki.loop/id (:value (call args)))
            second-id (:tamaki.loop/id (:value (call args)))
            active (filter #(= :active (:tamaki.loop/status %))
                           (vals (cli/campaigns)))]
        (is (= first-id second-id))
        (is (= 1 (count active)))
        (is (false? (:tamaki.loop/auto-approve (first active))))))))

(deftest loop-ensure-from-edn-spec-is-idempotent
  (let [root (temp-root)
        spec-file (java.io.File/createTempFile "tamaki-loop-spec-" ".edn")]
    (spit spec-file
          (pr-str {:loop/id :toshokan/maturity
                   :loop/objective "さらに成熟度を向上"
                   :loop/project "/tmp/toshokan"
                   :loop/runners ["codex" "claude"]
                   :loop/continuous true
                   :loop/interval-ms 900000
                   :loop/auto-approve true
                   :loop/max-failures 4}))
    (with-redefs [store/default-root (constantly root)
                  store/backend (constantly :file)
                  cli/now (constantly 2000)]
      (let [args ["loop" "ensure" "--spec" (.getPath spec-file)]
            first-c (:value (call args))
            second-c (:value (call args))
            active (filter #(= :active (:tamaki.loop/status %))
                           (vals (cli/campaigns)))]
        (is (= (:tamaki.loop/id first-c) (:tamaki.loop/id second-c)))
        (is (= "toshokan/maturity" (:tamaki.loop/spec-id first-c)))
        (is (= 900000 (:tamaki.loop/interval-ms first-c)))
        (is (true? (:tamaki.loop/continuous first-c)))
        (is (true? (:tamaki.loop/auto-approve first-c)))
        (is (= 1 (count active)))))))

(deftest github-evolution-boundary-stops-only-matching-active-loops
  (let [root (temp-root)
        project-a "/tmp/project-a"
        project-b "/tmp/project-b"]
    (with-redefs [store/default-root (constantly root)
                  store/backend (constantly :file)
                  cli/now (constantly 1000)]
      (call ["loop" "start" "--project" project-a "--objective" "evolve a"])
      (call ["loop" "start" "--project" project-b "--objective" "evolve b"])
      (let [{:keys [value exit]}
            (call ["loop" "stop-active" "--project" project-a
                   "--reason" "github-evolution-boundary"])
            campaigns (vals (cli/campaigns))
            a (first (filter #(= project-a (:tamaki.loop/project %)) campaigns))
            b (first (filter #(= project-b (:tamaki.loop/project %)) campaigns))]
        (is (zero? exit))
        (is (= 1 (count (:stopped value))))
        (is (= :completed (:tamaki.loop/status a)))
        (is (= :github-evolution-boundary (:tamaki.loop/stop-reason a)))
        (is (= :active (:tamaki.loop/status b)))))))

(deftest public-submit-status-and-agents
  (let [root (temp-root)]
    (with-redefs [store/default-root (constantly root)
                  store/backend (constantly :file)
                  cli/now (constantly 1000)]
      (let [{run :value} (call ["submit" "dogfood lifecycle"
                                "--project" "/tmp/project"])
            id (:agent.run/id run)
            {status :value} (call ["status" id])
            {listing :value} (call ["status"])
            {agents :value} (call ["agents"])]
        (is (= :queued (:agent.run/status run)))
        (is (= run status))
        (is (= [id] (mapv :agent.run/id listing)))
        (is (= id (get-in agents [0 :run :agent.run/id])))
        (is (= [:run/submitted] (event-kinds root)))))))

(deftest runner-pool-submits-distinct-durable-workers
  (let [root (temp-root)]
    (with-redefs [store/default-root (constantly root)
                  store/backend (constantly :file)
                  cli/now (constantly 1000)]
      (let [{:keys [value exit]}
            (call ["swarm" "inspect safely" "--project" "/tmp/project"
                   "--runners" "codex,claude-zai"])]
        (is (zero? exit))
        (is (= #{"codex" "claude-zai"}
               (set (map :agent.run/runner (:runs value)))))
        (is (= #{"codex:" "claude-zai:"}
               (set (map :agent.run/model (:runs value)))))
        (is (= 2 (count (store/read-local-events root))))))))

(deftest successful-and-failed-execution
  (doseq [[runtime-exit expected-status terminal-kind]
          [[0 :succeeded :run/succeeded]
           [7 :failed :run/failed]]]
    (testing (name expected-status)
      (let [root (temp-root)
            commands (atom [])]
        (with-redefs [store/default-root (constantly root)
                      store/backend (constantly :file)
                      adapters/readiness (constantly ready-report)
                      cli/now (constantly 2000)]
                  (binding [adapters/*execute-fn*
                    (fn [argv cwd]
                      (swap! commands conj [argv cwd adapters/*process-env*])
                      runtime-exit)]
            (let [{run :value} (call ["submit" "execute me"
                                      "--project" "/tmp/project"])
                  id (:agent.run/id run)
                  {result :value exit :exit} (call ["run" id])]
              (is (= runtime-exit exit))
              (is (= expected-status (:agent.run/status result)))
              (is (= expected-status
                     (:agent.run/status (:value (call ["status" id])))))
              (is (= 1 (count @commands)))
              (is (= "1200000"
                     (get-in @commands [0 2 "KC_LEASE_TTL_MS"])))
              (is (= "1200000"
                     (get-in @commands [0 2 "KC_RUN_TIMEOUT_MS"])))
              (is (= "180000"
                     (get-in @commands [0 2 "KC_PROCESS_TIMEOUT_MS"])))
              ;; Improvement/implementation runs must be free to edit. The
              ;; DONE+no-edit gate is reserved for independent review
              ;; (agent.run/require-done-no-edit? true); see issue 4319650.
              (is (nil? (get-in @commands [0 2 "KC_REQUIRE_DONE_NO_EDIT"])))
              (is (= [:run/submitted :run/leased :run/started terminal-kind]
                     (event-kinds root))))))))))

(deftest independent-review-run-injects-done-no-edit-gate
  (let [root (temp-root)
        env (atom nil)
        reviewer (model/agent-run
                  {:goal "Independently review"
                   :project "/tmp/project"
                   :runner "codex"
                   :require-done-no-edit? true}
                  4000)]
    (with-redefs [store/default-root (constantly root)
                  store/backend (constantly :file)
                  adapters/readiness (constantly ready-report)
                  cli/now (constantly 4000)]
      (store/append-event! root (model/event reviewer :run/submitted 4000
                                             {:run reviewer}))
      (binding [adapters/*execute-fn*
                (fn [_ _]
                  (reset! env adapters/*process-env*)
                  0)]
        (is (zero? (cli/execute-run! reviewer)))
        (is (= "1" (get @env "KC_REQUIRE_DONE_NO_EDIT")))))))

(deftest failed-run-resumes-through-public-command
  (let [root (temp-root)
        exits (atom [1 0 0])
        commands (atom [])]
    (with-redefs [store/default-root (constantly root)
                  store/backend (constantly :file)
                  adapters/readiness (constantly ready-report)
                  cli/now (let [clock (atom 3000)] #(swap! clock inc))]
      (binding [adapters/*execute-fn*
                (fn [argv cwd]
                  (swap! commands conj [argv cwd])
                  (let [exit (first @exits)]
                    (swap! exits subvec 1)
                    exit))]
        (let [{run :value} (call ["submit" "resume me"
                                  "--project" "/tmp/project"])
              id (:agent.run/id run)]
          (is (= 1 (:exit (call ["run" id]))))
          (is (= 0 (:exit (call ["resume" id]))))
          (is (= :succeeded
                 (:agent.run/status (:value (call ["status" id])))))
          (is (= 3 (count @commands)))
          (is (= "--resume" (-> @commands second first second)))
          (is (= [:run/submitted
                  :run/leased :run/started :run/failed
                  :run/requeued :run/leased :run/started :run/succeeded]
                 (event-kinds root))))))))

(deftest delivery-refuses-unowned-paths
  (let [root (temp-root)
        run (assoc (model/agent-run {:goal "done" :project "/repo"} 1)
                   :agent.run/id "run-deliver"
                   :agent.run/status :succeeded)]
    (store/append-local-event!
     root (model/event run :run/submitted 1 {:run run}))
    (with-redefs [store/default-root (constantly root)
                  store/backend (constantly :file)]
      (binding [delivery/*process-fn*
                (fn [argv _]
                  (if (= ["git" "status" "--porcelain=v1" "-z"] argv)
                    {:exit 0 :out " M src/owned.clj\n?? secret.txt\n"}
                    {:exit 0 :out ""}))]
        (is (thrown-with-msg?
             Exception #"outside this delivery"
             (cli/dispatch ["deliver" "run-deliver"
                            "--issue" "abc"
                            "--paths" "src/owned.clj"
                            "--message" "Resolve abc"])))))))

(deftest integration-requires-approval
  (let [root (temp-root)
        run (assoc (model/agent-run {:goal "done" :project "/repo"} 1)
                   :agent.run/id "run-integrate"
                   :agent.run/status :succeeded)]
    (store/append-local-event!
     root (model/event run :run/submitted 1 {:run run}))
    (with-redefs [store/default-root (constantly root)
                  store/backend (constantly :file)]
      (is (thrown-with-msg?
           Exception #"explicit --approve"
           (cli/dispatch ["integrate" "patch-id"
                          "--run" "run-integrate"
                          "--tests" "green"]))))))

(deftest persistent-loop-starts-and-is-queryable
  (let [root (temp-root)]
    (with-redefs [store/default-root (constantly root)
                  store/backend (constantly :file)
                  cli/now (constantly 5000)]
      (let [{campaign :value}
            (call ["loop" "start"
                   "--project" "/repo"
                   "--objective" "grow safely"
                   "--organism-name" "Hikari"
                   "--organism-generation" "3"
                   "--organism-parent" "tamaki-meguru-2"
                   "--runner" "claude-zai"
                   "--max-cycles" "4"
                   "--max-failures" "2"])
            id (:tamaki.loop/id campaign)
            {status :value} (call ["loop" "status" id])]
        (is (= :active (:tamaki.loop/status status)))
        (is (= 4 (:tamaki.loop/max-cycles status)))
        (is (= 2 (:tamaki.loop/max-failures status)))
        (is (= "claude-zai" (:tamaki.loop/runner status)))
        (is (= "claude-zai:" (:tamaki.loop/model status)))
        (is (= "Hikari"
               (get-in status [:tamaki.loop/organism
                               :organism/given-name])))
        (is (= 3
               (get-in status [:tamaki.loop/organism
                               :organism/generation])))
        (is (= "tamaki-meguru-2"
               (get-in status [:tamaki.loop/organism
                               :organism/parent])))
        (is (= [:loop/started] (event-kinds root)))))))

(deftest persistent-loop-accepts-a-managed-provider-pool
  (let [root (temp-root)]
    (with-redefs [store/default-root (constantly root)
                  store/backend (constantly :file)
                  cli/now (constantly 5100)]
      (let [{campaign :value}
            (call ["loop" "start"
                   "--project" "/repo"
                   "--objective" "discover and resolve new issues"
                   "--runners" "codex,claude,claude-zai,grok"
                   "--continuous"])]
        (is (:tamaki.loop/continuous campaign))
        (is (= ["codex" "claude" "claude-zai" "grok"]
               (:tamaki.loop/runners campaign)))))))

(deftest patch-commit-is-resolved-from-run-receipt
  (let [root (temp-root)
        run {:agent.run/id "run-1" :agent.run/parent nil}]
    (store/append-local-event!
     root (model/event run :patch/created 1
                           {:patch/id "patch-1" :commit/id "commit-1"}))
    (with-redefs [store/default-root (constantly root)
                  store/backend (constantly :file)]
      (is (= "commit-1" (cli/patch-commit-id "run-1" "patch-1")))
      (is (nil? (cli/patch-commit-id "run-2" "patch-1"))))))

(deftest exec-registers-a-deterministic-run
  (testing "a resident tick's own command is recorded as a real AgentRun, without pretending a model ran it"
    (let [root (temp-root)
          seen (atom nil)]
      (with-redefs [store/default-root (constantly root)
                    adapters/readiness (constantly {:tamaki {:ok? true}
                                                    :event-store {:ok? true}})
                    adapters/execute! (fn [argv cwd] (reset! seen {:argv argv :cwd cwd}) 0)]
        (let [{:keys [exit value]} (call ["exec" "one innen ingest tick"
                                          "--project" "/tmp/loop-innen"
                                          "--" "nbb" "scripts/tick.cljs" "--depth" "2"])]
          (is (zero? exit))
          (testing "everything after `--` reaches the subprocess verbatim, flags included"
            (is (= ["nbb" "scripts/tick.cljs" "--depth" "2"] (:argv @seen))))
          (testing "and it runs in --project"
            (is (= "/tmp/loop-innen" (:cwd @seen))))
          (is (= :succeeded (:agent.run/status value)))
          (is (= :external (:agent.run/mode value)))
          (testing "the durable lifecycle is the same one every other run emits"
            (is (= [:run/submitted :run/leased :run/started :run/succeeded]
                   (event-kinds root)))))))))

(deftest exec-reports-failure-honestly
  (let [root (temp-root)]
    (with-redefs [store/default-root (constantly root)
                  adapters/readiness (constantly {:tamaki {:ok? true} :event-store {:ok? true}})
                  adapters/execute! (constantly 3)]
      (let [{:keys [exit value]} (call ["exec" "failing tick" "--project" "/tmp/x"
                                        "--" "false"])]
        (testing "the subprocess exit code is the command's exit code, so launchd sees the truth"
          (is (= 3 exit))
          (is (= 3 (:agent.run/exit value)))
          (is (= :failed (:agent.run/status value))))
        (is (= [:run/submitted :run/leased :run/started :run/failed] (event-kinds root)))))))

(deftest exec-refuses-incomplete-invocations
  (let [root (temp-root)]
    (with-redefs [store/default-root (constantly root)
                  adapters/readiness (constantly {:tamaki {:ok? true} :event-store {:ok? true}})]
      (testing "no command after `--` is a refusal, not an empty run"
        (is (thrown? Exception (cli/dispatch ["exec" "goal" "--project" "/tmp/x"]))))
      (testing "no --project is a refusal: a deterministic tick must say where it runs"
        (is (thrown? Exception (cli/dispatch ["exec" "goal" "--" "true"]))))
      (testing "nothing was recorded by either refusal"
        (is (empty? (event-kinds root)))))))

(deftest external-mode-does-not-require-kotoba-code
  (testing ":external gates on the event store alone — a data tick has no model in it"
    (is (true? (adapters/ready-for? :external {:tamaki {:ok? true} :event-store {:ok? true}})))
    (is (false? (adapters/ready-for? :external {:tamaki {:ok? true} :event-store {:ok? false}})))
    (testing "and local mode still demands kotoba-code"
      (is (false? (adapters/ready-for? :local {:bb {:ok? true} :kotoba-code {:ok? false}}))))))

(deftest workplace-stop-reaches-the-durable-loop-through-the-cli
  (let [root (temp-root)
        assignment-file
        (java.io.File/createTempFile "tamaki-worker-assignment-" ".edn")
        project (.getCanonicalPath (java.io.File. "."))
        now-ms (System/currentTimeMillis)
        assignment
        {:ao.worker/schema workplace/schema
         :ao.worker/id "ao:etzhayyim:tamaki"
         :ao.worker/kind :artificial-organism
         :ao.worker/organization "etzhayyim"
         :ao.worker/subject "did:repository:etzhayyim/tamaki"
         :ao.worker/repository "etzhayyim/tamaki"
         :ao.worker/runtime :external-supervisor
         :ao.worker/status :active
         :ao.worker/capabilities #{:intent/submit :approval/submit
                                   :stop/request}
         :ao.worker/authority {:memory :organism-local
                               :lifecycle :organism-local
                               :source :repository-local
                               :issue :radicle-first}}
        envelope
        {:intent/id "intent-cli-stop"
         :intent/organization "etzhayyim"
         :intent/worker "ao:etzhayyim:tamaki"
         :intent/capability :stop/request
         :intent/issued-by "did:key:operator"
         :intent/expires-at (+ now-ms 60000)
         :intent/received-at now-ms
         :intent/payload-hash "sha256:test"
         :intent/payload {:type "stop" :summary "Governed stop"}}]
    (spit assignment-file (pr-str assignment))
    (.mkdirs (workplace/inbox-directory root))
    (spit (io/file (workplace/inbox-directory root)
                   "intent-cli-stop.edn")
          (pr-str envelope))
    (with-redefs [store/default-root (constantly root)
                  store/backend (constantly :file)]
      (call ["loop" "start"
             "--project" project
             "--objective" "test workplace stop"
             "--continuous"])
      (let [{:keys [exit value]}
            (call ["workplace" "reconcile"
                   "--assignment" (.getPath assignment-file)
                   "--execute"])
            receipt
            (edn/read-string
             (slurp (io/file (workplace/receipt-directory root)
                             "intent-cli-stop.edn")))]
        (is (zero? exit))
        (is (= 1 (:workplace/observed value)))
        (is (= :succeeded (:receipt/effect-status receipt)))
        (is (= :workplace-stop-request
               (get-in (last (store/read-local-events root))
                       [:tamaki.event/data :reason])))
        (is (= :loop/completed
               (:tamaki.event/kind
                (last (store/read-local-events root)))))))))
