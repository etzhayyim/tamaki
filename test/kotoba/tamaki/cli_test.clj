(ns kotoba.tamaki.cli-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.adapters :as adapters]
            [kotoba.tamaki.cli :as cli]
            [kotoba.tamaki.delivery :as delivery]
            [kotoba.tamaki.model :as model]
            [kotoba.tamaki.store :as store]
            [kotoba.tamaki.supervisor :as supervisor]))

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
                      (swap! commands conj [argv cwd])
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
              (is (= [:run/submitted :run/leased :run/started terminal-kind]
                     (event-kinds root))))))))))

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
                  (if (= ["git" "status" "--porcelain"] argv)
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
