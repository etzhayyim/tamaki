(ns kotoba.tamaki.family
  "Repository-bound artificial-organism family projection.

  GitHub is observed, while the public family spec defines membership and
  invariants. The resulting registry is local runtime state, never a public
  inventory of private work."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.nio.file Files StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def family-version 1)

(defn read-spec [path]
  (edn/read-string (slurp (io/file path))))

(defn validate-spec! [spec]
  (let [required [:family/id :family/organization
                  :family/representative-repository
                  :family/membership :family/invariant]
        missing (remove #(contains? spec %) required)
        membership (:family/membership spec)
        invariant (:family/invariant spec)]
    (when (seq missing)
      (throw (ex-info "FamilySpec is missing required keys"
                      {:family/missing (vec missing)})))
    (when-not (= :all-owned-repositories (:rule membership))
      (throw (ex-info "Only complete organization membership is supported"
                      {:family/membership membership})))
    (when-not (= {:repository-per-ao 1 :ao-per-repository 1}
                 (select-keys invariant
                              [:repository-per-ao :ao-per-repository]))
      (throw (ex-info "FamilySpec must preserve the 1 repo = 1 AO invariant"
                      {:family/invariant invariant})))
    spec))

(defn repository->ao [spec repository]
  (let [organization (:family/organization spec)
        slug (:nameWithOwner repository)
        repo-name (:name repository)]
    (when-not (= slug (str organization "/" repo-name))
      (throw (ex-info "Repository is outside the declared organization"
                      {:family/organization organization
                       :repository slug})))
    {:ao/version 1
     :ao/id (str "ao:github:" slug)
     :ao/family (:family/id spec)
     :ao/family-name (get-in spec [:family/defaults :ao/family-name] "Tamaki")
     :ao/given-name repo-name
     :ao/repository {:slug slug
                     :url (:url repository)
                     :visibility (some-> (:visibility repository)
                                         name
                                         keyword)
                     :default-branch
                     (get-in repository [:defaultBranchRef :name])
                     :archived? (boolean (:isArchived repository))}
     :ao/status (if (:isArchived repository) :dormant :active)
     :ao/representative?
     (= repo-name (:family/representative-repository spec))
     :ao/authority :repository-local
     :ao/genome :git-history
     :ao/memory [:issues :patches :events]}))

(defn projection [spec repositories observed-at]
  (validate-spec! spec)
  (let [organisms (->> repositories
                       (map #(repository->ao spec %))
                       (sort-by :ao/id)
                       vec)
        ids (map :ao/id organisms)
        slugs (map #(get-in % [:ao/repository :slug]) organisms)
        representatives (filter :ao/representative? organisms)]
    (when-not (= (count ids) (count (distinct ids)))
      (throw (ex-info "More than one repository projected to the same AO" {})))
    (when-not (= (count slugs) (count (distinct slugs)))
      (throw (ex-info "A repository projected to more than one AO" {})))
    (when-not (= 1 (count representatives))
      (throw (ex-info "Family must have exactly one representative AO"
                      {:family/representative-count
                       (count representatives)})))
    {:family.registry/version family-version
     :family/id (:family/id spec)
     :family/organization (:family/organization spec)
     :family/observed-at observed-at
     :family/representative (:ao/id (first representatives))
     :family/invariant {:repository-per-ao 1 :ao-per-repository 1}
     :family/summary
     {:total (count organisms)
      :active (count (filter #(= :active (:ao/status %)) organisms))
      :dormant (count (filter #(= :dormant (:ao/status %)) organisms))
      :representatives (count representatives)}
     :family/organisms organisms}))

(defn registry-file [root]
  (io/file root "families" "etzhayyim.edn"))

(defn read-registry [root]
  (let [file (registry-file root)]
    (when (.isFile file)
      (edn/read-string (slurp file)))))

(defn write-registry! [root registry]
  (let [target (registry-file root)
        parent (.toPath (.getParentFile target))
        _ (Files/createDirectories parent
                                   (make-array FileAttribute 0))
        temp (Files/createTempFile parent ".etzhayyim-" ".edn"
                                   (make-array FileAttribute 0))]
    (spit (.toFile temp) (str (pr-str registry) "\n"))
    (try
      (Files/move temp (.toPath target)
                  (into-array StandardCopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      (catch java.nio.file.AtomicMoveNotSupportedException _
        (Files/move temp (.toPath target)
                    (into-array StandardCopyOption
                                [StandardCopyOption/REPLACE_EXISTING]))))
    registry))

(defn public-summary [registry]
  (select-keys registry
               [:family.registry/version :family/id :family/organization
                :family/observed-at :family/representative
                :family/invariant :family/summary]))
