(ns kotoba.tamaki.lineage
  "Finite organism identity, relational wellbecoming, and governed succession.")

(def day-ms 86400000)
(def default-lifetime-ms (* 30 day-ms))

(def wellbeing-dimensions
  [:human-agency :relational-trust :inheritable-learning
   :future-optionality :succession-integrity])

(defn clamp [value]
  (-> (double (or value 0.0)) (max 0.0) (min 1.0)))

(defn organism
  "Create one finite Tamaki individual. Expiry is an immutable lease boundary;
  callers may request a shorter lifetime but never more than 30 days."
  [{:keys [id family-name given-name generation parent born-at lifetime-ms]
    :or {family-name "Tamaki" generation 1}}
   now-ms]
  (let [born-at (or born-at now-ms)
        lifetime-ms (or lifetime-ms default-lifetime-ms)]
    (when (or (not (pos-int? lifetime-ms))
              (> lifetime-ms default-lifetime-ms))
      (throw (ex-info "Organism lifetime must be within 30 days"
                      {:lifetime-ms lifetime-ms
                       :maximum-ms default-lifetime-ms})))
    (when (or (not (string? given-name)) (clojure.string/blank? given-name))
      (throw (ex-info "Organism requires a given name"
                      {:field :organism/given-name})))
    {:organism/version 1
     :organism/id (or id
                      (str (clojure.string/lower-case family-name) "-"
                           (clojure.string/lower-case given-name) "-"
                           generation))
     :organism/family-name family-name
     :organism/given-name given-name
     :organism/generation generation
     :organism/parent parent
     :organism/born-at born-at
     :organism/expires-at (+ born-at lifetime-ms)
     :organism/lifetime-ms lifetime-ms}))

(defn life-phase [individual now-ms]
  (let [born (:organism/born-at individual)
        expires (:organism/expires-at individual)
        age (- now-ms born)
        lifetime (- expires born)
        ratio (if (pos? lifetime) (/ age (double lifetime)) 1.0)]
    (cond
      (< now-ms born) :not-born
      (>= now-ms expires) :expired
      (< ratio 0.70) :active-life
      (< ratio 0.90) :succession-planning
      :else :handover)))

(defn expired? [individual now-ms]
  (= :expired (life-phase individual now-ms)))

(defn lineage-vitality
  "Geometric mean: no dimension can be compensated away by maximizing another.
  This models relational becoming while keeping agency and consent as boundaries."
  [observation]
  (let [values (map #(clamp (get observation % 0.0)) wellbeing-dimensions)]
    (Math/pow (reduce * values) (/ 1.0 (count wellbeing-dimensions)))))

(defn action-gate
  "Return the organism-level gate for an action. Relational existence does not
  erase human boundaries: low agency pauses work and reproduction always needs
  explicit, signed human consent."
  [{:keys [individual now-ms wellbecoming action human-consent? consent-signature]}]
  (cond
    (expired? individual now-ms) :expired
    (< (clamp (:human-agency wellbecoming)) 0.5) :repair-relationship
    (and (= action :reproduce)
         (not (and human-consent? (seq consent-signature))))
    :approval-required
    (< (lineage-vitality wellbecoming) 0.35) :rest-and-repair
    :else :allowed))

(defn inheritable-memes
  "Only explicitly inheritable memes with provenance and consent scope cross a
  generation boundary."
  [memes]
  (->> memes
       (filter #(and (:meme/inheritable? %)
                     (seq (:meme/provenance %))
                     (:meme/consent %)))
       vec))

(defn succession-plan
  [{:keys [parent child-name now-ms wellbecoming memes
           human-consent? consent-signature]}]
  (let [gate (action-gate {:individual parent :now-ms now-ms
                           :wellbecoming wellbecoming :action :reproduce
                           :human-consent? human-consent?
                           :consent-signature consent-signature})]
    (cond-> {:succession/status (if (= :allowed gate) :approved :blocked)
             :succession/gate gate
             :succession/parent (:organism/id parent)
             :succession/proposed-name child-name
             :succession/memes (inheritable-memes memes)}
      (= :allowed gate)
      (assoc :succession/child
             (organism {:family-name (:organism/family-name parent)
                        :given-name child-name
                        :generation (inc (:organism/generation parent))
                        :parent (:organism/id parent)}
                       now-ms)
             :succession/consent-signature consent-signature))))
