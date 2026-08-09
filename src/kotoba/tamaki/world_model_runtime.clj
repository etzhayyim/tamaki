(ns kotoba.tamaki.world-model-runtime
  "JVM-only adapter to the canonical OASIS XMILE capability.

  Tamaki's general CLI remains Babashka-fast. The `world-model` command is
  routed to the JVM because org-oasis-open-xmile deliberately uses the JVM's
  hardened XML parser at its text boundary."
  (:require [xmile.execute :as execute]
            [xmile.validate :as validate]
            [xmile.xml :as xml]))

(defn validation-errors [model]
  (let [problems (validate/validate model)]
    (when-not (validate/valid? problems)
      (validate/errors problems))))

(defn document-validation-errors [document]
  (let [problems (validate/validate-doc document)]
    (when-not (validate/valid? problems)
      (validate/errors problems))))

(defn run [model] (execute/run model))
(defn emit-string [document] (xml/emit-string document))
