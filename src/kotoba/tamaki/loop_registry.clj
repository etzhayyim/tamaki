(ns kotoba.tamaki.loop-registry
  "EDN LoopSpec registry: durable agent-loop declarations Tamaki discovers
  and drives. Specs are source of truth; CLI flags are overrides, not
  the primary registration surface."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def loop-spec-keys
  #{:loop/id :loop/objective :loop/project :loop/runners :loop/runner
    :loop/model :loop/continuous :loop/interval-ms :loop/max-cycles
    :loop/max-failures :loop/auto-approve :loop/enabled
    :loop/organism-name :loop/organism-generation :loop/organism-parent
    :loop/workspace-env :loop/description :loop/tags :loop/fitness
    :loop/ao-id})

(defn env
  "Lookup process environment. Public so tests can rebind without shell env."
  ([k] (env k nil))
  ([k default]
   (or (not-empty (System/getenv k)) default)))

(defn loop-id [value]
  (cond
    (keyword? value) value
    (and (string? value) (not (str/blank? value))) (keyword value)
    :else (throw (ex-info "LoopSpec requires :loop/id" {:value value}))))

(defn spec-id-str
  "Stable string form for durable campaign matching. Namespaced keywords become
  `ns/name` (no leading colon)."
  [value]
  (cond
    (keyword? value) (subs (str value) 1)
    (string? value) (let [s (str/trim value)]
                      (if (str/starts-with? s ":") (subs s 1) s))
    :else (str value)))

(defn- normalize-runners
  "Accept either simple string/keyword ids or weighted {:runner :weight} maps
  (ActorSpec shape). Result is ordered vector of runner id strings."
  [runners runner]
  (let [from-runners
        (mapv (fn [entry]
                (cond
                  (string? entry) entry
                  (keyword? entry) (name entry)
                  (map? entry)
                  (let [r (or (:runner entry) (:id entry))]
                    (cond
                      (string? r) r
                      (keyword? r) (name r)
                      :else (throw (ex-info "LoopSpec runner entry needs :runner"
                                            {:entry entry}))))
                  :else (throw (ex-info "Invalid LoopSpec runner entry"
                                        {:entry entry}))))
              (or runners []))]
    (if (seq from-runners)
      from-runners
      (cond
        (string? runner) [runner]
        (keyword? runner) [(name runner)]
        :else []))))

(defn resolve-project
  "Resolve :loop/project. Absolute paths pass through. Relative paths resolve
  against the process cwd by default. When the LoopSpec sets
  :loop/workspace-env (e.g. COM_JUNKAWASAKI_ROOT), resolve relative to that
  environment variable instead — used for monorepo-child paths such as
  orgs/kotoba-lang/toshokan."
  [spec]
  (let [raw (str (:loop/project spec))
        file (io/file raw)]
    (if (.isAbsolute file)
      (.getCanonicalPath file)
      (let [workspace-env (:loop/workspace-env spec)
            workspace (when workspace-env (env workspace-env))
            base (io/file (or workspace (System/getProperty "user.dir")))]
        (when (and workspace-env (str/blank? workspace))
          (throw (ex-info
                  (str "LoopSpec :loop/workspace-env " workspace-env
                       " is unset; export it or use an absolute :loop/project")
                  {:loop/id (:loop/id spec)
                   :loop/workspace-env workspace-env
                   :loop/project raw})))
        (.getCanonicalPath (io/file base raw))))))

(defn validate-spec
  [spec]
  (let [unknown-keys (seq (remove loop-spec-keys (keys spec)))
        id (loop-id (:loop/id spec))
        objective (:loop/objective spec)
        project (:loop/project spec)
        runners (normalize-runners (:loop/runners spec) (:loop/runner spec))
        interval (or (:loop/interval-ms spec) 60000)
        max-cycles (or (:loop/max-cycles spec) 10)
        max-failures (or (:loop/max-failures spec) 3)
        continuous (boolean (:loop/continuous spec))
        enabled (if (contains? spec :loop/enabled)
                  (boolean (:loop/enabled spec))
                  true)]
    (when unknown-keys
      (throw (ex-info "LoopSpec contains unknown keys"
                      {:loop/id id :unknown-keys (vec (sort unknown-keys))})))
    (when (str/blank? (str objective))
      (throw (ex-info "LoopSpec requires :loop/objective" {:loop/id id})))
    (when (str/blank? (str project))
      (throw (ex-info "LoopSpec requires :loop/project" {:loop/id id})))
    (when-not (pos-int? interval)
      (throw (ex-info "LoopSpec :loop/interval-ms must be a positive integer"
                      {:loop/id id :loop/interval-ms interval})))
    (when-not (pos-int? max-cycles)
      (throw (ex-info "LoopSpec :loop/max-cycles must be a positive integer"
                      {:loop/id id :loop/max-cycles max-cycles})))
    (when-not (pos-int? max-failures)
      (throw (ex-info "LoopSpec :loop/max-failures must be a positive integer"
                      {:loop/id id :loop/max-failures max-failures})))
    (when (and (empty? runners) (str/blank? (str (:loop/runner spec))))
      ;; empty runners is allowed (tamaki defaults at ensure time) but warn
      ;; only by leaving the vector empty — same as CLI without --runners.
      nil)
    (-> spec
        (assoc :loop/id id
               :loop/objective (str objective)
               :loop/project (str project)
               :loop/runners runners
               :loop/interval-ms interval
               :loop/max-cycles max-cycles
               :loop/max-failures max-failures
               :loop/continuous continuous
               :loop/auto-approve (boolean (:loop/auto-approve spec))
               :loop/enabled enabled)
        (cond-> (:loop/runner spec)
          (assoc :loop/runner (let [r (:loop/runner spec)]
                                (if (keyword? r) (name r) (str r))))))))

(defn read-spec
  ([path] (read-spec path {}))
  ([path {:keys [resolve-project?] :or {resolve-project? true}}]
   (let [file (io/file path)]
     (when-not (.isFile file)
       (throw (ex-info "LoopSpec file not found" {:path path})))
     (let [spec (validate-spec (edn/read-string (slurp file)))
           with-path (assoc spec :loop/spec-path (.getCanonicalPath file))]
       (if resolve-project?
         (assoc with-path :loop/project (resolve-project with-path))
         with-path)))))

(defn default-search-dirs
  "Ordered dirs Tamaki scans for LoopSpec EDN files."
  []
  (let [cwd (System/getProperty "user.dir")
        home (System/getProperty "user.home")
        workspace (or (env "TAMAKI_WORKSPACE")
                      (env "FLEET_ROOT")
                      (env "COM_JUNKAWASAKI_ROOT"))
        configured (env "TAMAKI_LOOPS_DIR")
        configured-many (env "TAMAKI_LOOPS_PATH")]
    (->> (concat
          (when configured [configured])
          (when configured-many
            (str/split configured-many #":"))
          [(io/file cwd "loops")
           (io/file cwd "actors" "loops")
           (io/file cwd ".tamaki" "loops")
           (when home (io/file home ".config" "tamaki" "loops"))
           (when workspace (io/file workspace "loops"))
           (when workspace (io/file workspace ".tamaki" "loops"))]
          )
         (remove nil?)
         (map io/file)
         (filter #(.isDirectory %))
         distinct
         vec)))

(defn- loop-edn-file?
  [file]
  (let [name (.getName file)]
    (and (.isFile file)
         (str/ends-with? name ".edn")
         (not (str/starts-with? name "."))
         ;; skip private target / observation helpers if co-located
         (not (str/includes? name "targets"))
         (not (str/includes? name "observation"))
         (not (str/includes? name "example")))))

(defn discover-paths
  "Return canonical paths of candidate LoopSpec files."
  ([] (discover-paths (default-search-dirs)))
  ([dirs]
   (->> dirs
        (mapcat (fn [dir]
                  (let [f (io/file dir)]
                    (when (.isDirectory f)
                      (->> (.listFiles f)
                           (filter loop-edn-file?)
                           (map #(.getCanonicalPath %)))))))
        (remove nil?)
        distinct
        sort
        vec)))

(defn discover-specs
  "Load and validate every discoverable LoopSpec. Invalid files are skipped
  only when :skip-invalid? is true (default false throws)."
  ([] (discover-specs {}))
  ([{:keys [dirs skip-invalid?] :or {skip-invalid? false}}]
   (let [paths (discover-paths (or dirs (default-search-dirs)))]
     (into []
           (keep (fn [path]
                   (try
                     (read-spec path)
                     (catch Exception e
                       (if skip-invalid?
                         nil
                         (throw (ex-info (str "Failed to load LoopSpec " path)
                                         {:path path :cause (.getMessage e)}
                                         e)))))))
           paths))))

(defn ensure-options
  "CLI-shaped options map derived from a validated LoopSpec. Optional
  `overrides` (parsed CLI options) take precedence for any non-nil key."
  ([spec] (ensure-options spec {}))
  ([spec overrides]
   (let [runners (:loop/runners spec)
         runner (or (:loop/runner spec) (first runners))]
     (merge
      {:project (:loop/project spec)
       :objective (:loop/objective spec)
       :runners (when (seq runners) (str/join "," runners))
       :runner runner
       :model (:loop/model spec)
       :interval-ms (str (:loop/interval-ms spec))
       :max-cycles (str (:loop/max-cycles spec))
       :max-failures (str (:loop/max-failures spec))
       :continuous (:loop/continuous spec)
       :auto-approve (:loop/auto-approve spec)
       :organism-name (:loop/organism-name spec)
       :organism-generation (when-let [g (:loop/organism-generation spec)]
                              (str g))
       :organism-parent (:loop/organism-parent spec)
       :ao-id (:loop/ao-id spec)
       ;; Internal: durable match key so objective prose can evolve.
       :spec-id (spec-id-str (:loop/id spec))
       :spec-path (:loop/spec-path spec)}
      (into {} (remove (fn [[_ v]] (or (nil? v) (= "" v))) overrides))))))

(defn compatible-campaign?
  "True when an active campaign is the live instance of this LoopSpec.
  Prefer :tamaki.loop/spec-id when present; fall back to project+objective+
  runners+auto-approve (legacy ensure)."
  [spec campaign]
  (let [spec-id (spec-id-str (:loop/id spec))
        campaign-spec (:tamaki.loop/spec-id campaign)
        runners (:loop/runners spec)]
    (if (and campaign-spec (not (str/blank? (str campaign-spec))))
      (and (= spec-id (spec-id-str campaign-spec))
           (= (:loop/project spec) (:tamaki.loop/project campaign)))
      (and (= (:loop/project spec) (:tamaki.loop/project campaign))
           (= (:loop/objective spec) (:tamaki.loop/objective campaign))
           (= runners (vec (:tamaki.loop/runners campaign)))
           (= (boolean (:loop/auto-approve spec))
              (boolean (:tamaki.loop/auto-approve campaign)))))))

(defn summarize-spec
  "Operator-facing view of a LoopSpec plus optional campaign match."
  [spec campaign]
  (cond-> {:loop/id (:loop/id spec)
           :loop/enabled (:loop/enabled spec)
           :loop/objective (:loop/objective spec)
           :loop/project (:loop/project spec)
           :loop/runners (:loop/runners spec)
           :loop/continuous (:loop/continuous spec)
           :loop/interval-ms (:loop/interval-ms spec)
           :loop/max-failures (:loop/max-failures spec)
           :loop/auto-approve (:loop/auto-approve spec)
           :loop/spec-path (:loop/spec-path spec)
           :loop/description (:loop/description spec)
           :loop/tags (:loop/tags spec)
           :campaign/matched? (boolean campaign)}
    campaign
    (assoc :campaign/id (:tamaki.loop/id campaign)
           :campaign/status (:tamaki.loop/status campaign)
           :campaign/cycles (:tamaki.loop/cycles campaign)
           :campaign/failures (:tamaki.loop/failures campaign)
           :campaign/last-result (:tamaki.loop/last-result campaign))))
