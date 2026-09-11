(ns kotoba.tamaki.visibility
  "Fail-closed repository and issue-authority policy.")

(defn policy
  ([organism] (policy organism {}))
  ([organism {:keys [public-organisms private-organisms]}]
   (cond
    (contains? (set public-organisms) organism)
    {:repository/visibility :public
     :issue/authority :radicle
     :github/mirror :public-allowed}

    (contains? (set private-organisms) organism)
    {:repository/visibility :private
     :issue/authority :github-private
     :github/mirror :primary
     :local-ledger :audit}

    :else
    {:repository/visibility :private
     :issue/authority :blocked
     :github/mirror :blocked})))

(defn- github-mirror-for
  "Mirror mode aligned with `policy` rows: private GitHub orgs keep GitHub as
  the primary surface; public orgs may mirror publicly; everything else is
  blocked so an unknown or private actor cannot leak through a GitHub path."
  [visibility authority]
  (cond
    (= :github-private authority) :primary
    (= :public visibility) :public-allowed
    :else :blocked))

(defn validate-actor
  "Reject a private actor that could publish source or issue metadata through
  Radicle. Unknown organisms are also private and blocked by default.

  Federated fields are filled with fail-closed defaults and the GitHub mirror
  mode matches `policy` so public Radicle actors are not silently marked
  mirror-blocked."
  [spec]
  (if-not (or (:actor/organism spec)
              (:actor/repository-visibility spec)
              (:actor/issue-authority spec))
    ;; Legacy, non-federated actors are left unchanged. Federation migration
    ;; must assign an organism before this policy can make a safe determination.
    spec
    (let [visibility (or (:actor/repository-visibility spec) :private)
          authority (or (:actor/issue-authority spec) :blocked)
          capabilities (set (:actor/capabilities spec))]
      (when (and (= :private visibility)
                 (or (= :radicle authority)
                     (contains? capabilities :radicle)))
        (throw (ex-info "Private actor cannot use Radicle publication"
                        {:actor/id (:actor/id spec)
                         :actor/organism (:actor/organism spec)})))
      (assoc spec
             :actor/repository-visibility visibility
             :actor/issue-authority authority
             :actor/github-mirror (github-mirror-for visibility authority)))))
