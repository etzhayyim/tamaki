(ns kotoba.tamaki.lineage
  "Tamaki adapter for the shared finite-lineage model.

  Finite organism identity, relational wellbecoming and governed succession
  are owned by `kotoba-lang/ao` (`ao.lineage`). Tamaki supplies only the two
  values that were always tamaki's rather than the model's: the `Tamaki`
  family name and this fleet's 30-day maximum lease (ADR-0002).

  Same adapter shape as `kotoba.tamaki.model` and `kotoba.tamaki.capability`."
  (:require [ao.lineage :as lineage]))

(def day-ms lineage/day-ms)
(def default-lifetime-ms lineage/default-max-lifetime-ms)
(def wellbeing-dimensions lineage/wellbeing-dimensions)

(def family-name
  "The family this fleet's incarnations are named into. ADR-0001: the durable
  AO derives its name from its repository slug; this is the incarnation's
  given family, not the AO's identity."
  "Tamaki")

(defn clamp [value] (lineage/clamp value))

(defn organism
  "Create one finite Tamaki individual, defaulting the family name that the
  shared library deliberately does not assume."
  [spec now-ms]
  (lineage/organism (merge {:family-name family-name} spec)
                    now-ms default-lifetime-ms))

(defn life-phase [individual now-ms] (lineage/life-phase individual now-ms))
(defn expired? [individual now-ms] (lineage/expired? individual now-ms))
(defn lineage-vitality [observation] (lineage/lineage-vitality observation))
(defn action-gate [opts] (lineage/action-gate opts))
(defn inheritable-memes [memes] (lineage/inheritable-memes memes))

(defn succession-plan [opts]
  (lineage/succession-plan (assoc opts :max-lifetime-ms default-lifetime-ms)))
