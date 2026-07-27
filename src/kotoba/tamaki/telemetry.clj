(ns kotoba.tamaki.telemetry
  "Pure conversion of provider snapshots into Tamaki business observations.

  Provider credentials and concrete mappings live in the local control plane."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time Instant LocalDate ZoneOffset]
           [java.time.format DateTimeParseException]))

(defn read-edn [path]
  (edn/read-string (slurp (io/file path))))

(defn read-spec [path]
  (let [file (.getCanonicalFile (io/file path))
        spec (read-edn file)
        source (:collector/source spec)
        root-env (:collector/source-root-env spec)
        root (when root-env (System/getenv root-env))
        source-file (cond
                      (and root source) (io/file root source)
                      source (io/file (.getParentFile file) source))]
    (cond-> spec
      source-file (assoc :collector/source
                         (.getCanonicalPath source-file)))))

(defn- value-at [snapshot selector]
  (cond
    (vector? selector) (get-in snapshot selector)
    (number? selector) selector
    (nil? selector) nil
    :else (throw (ex-info "Telemetry selector must be a path vector or number"
                          {:selector selector}))))

(defn- map-values [snapshot mappings]
  (into {}
        (map (fn [[key selector]] [key (value-at snapshot selector)]))
        mappings))

(defn- parse-timestamp-ms
  "Normalize a provider timestamp into epoch milliseconds.

  Accepts numbers, digit-only epoch-ms strings, ISO-8601 instants, and
  calendar dates (UTC start of day). Unparseable values return nil so
  collectors fail closed to :fresh? false rather than aborting KPI intake."
  [value]
  (cond
    (number? value) (long value)
    (string? value)
    (let [trimmed (str/trim value)]
      (when-not (str/blank? trimmed)
        (or (when (re-matches #"\d+" trimmed)
              (try (Long/parseLong trimmed)
                   (catch NumberFormatException _ nil)))
            (try (.toEpochMilli (Instant/parse trimmed))
                 (catch DateTimeParseException _ nil))
            (try (-> (LocalDate/parse trimmed)
                     (.atStartOfDay ZoneOffset/UTC)
                     .toInstant
                     .toEpochMilli)
                 (catch DateTimeParseException _ nil)))))
    :else nil))

(defn observed-at-ms [snapshot]
  (when-let [value (or (:observed-at snapshot) (:as-of snapshot))]
    (parse-timestamp-ms value)))

(defn collect
  [spec now-ms]
  (let [snapshot (read-edn (:collector/source spec))
        observed-at (observed-at-ms snapshot)
        max-age-ms (long (or (:collector/max-age-ms spec) 172800000))
        age-ms (when observed-at (max 0 (- now-ms observed-at)))
        ;; Always boolean: business/summary treats only explicit false as
        ;; :stale. A missing or unparseable provider timestamp must not look
        ;; "observed" with fabricated KPIs — fail closed to not-fresh.
        fresh? (boolean (and observed-at (<= age-ms max-age-ms)))
        mappings (:collector/mappings spec)
        observation
        {:domain (:collector/domain spec)
         :source (:collector/id spec)
         :source-observed-at observed-at
         :collected-at now-ms
         :fresh? fresh?
         :period-days (or (:collector/period-days spec) 7)
         :stocks (map-values snapshot (:stocks mappings))
         :flows (map-values snapshot (:flows mappings))
         :rates (assoc (map-values snapshot (:rates mappings))
                       :confidence (if fresh?
                                     (double (or (:collector/confidence spec)
                                                 0.8))
                                     0.0))}]
    (when (str/blank? (str (:collector/domain spec)))
      (throw (ex-info "Collector requires :collector/domain" {})))
    {:collector/id (:collector/id spec)
     :collector/domain (:collector/domain spec)
     :collector/fresh? fresh?
     :collector/age-ms age-ms
     :observation observation}))
