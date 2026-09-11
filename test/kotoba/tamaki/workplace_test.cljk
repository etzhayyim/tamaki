(ns kotoba.tamaki.workplace-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kotoba.tamaki.workplace :as workplace]))

(def assignment
  (workplace/assignment
   {:worker-id "ao:etzhayyim:tamaki"
    :organization "etzhayyim"
    :subject "did:repository:etzhayyim/tamaki"
    :repository "etzhayyim/tamaki"
    :incarnation {:id "Tamaki Hikari" :expires-at 2000}
    :capabilities #{:intent/submit}}))

(deftest workplace-does-not-own-the-organism
  (is (= :external-supervisor (:ao.worker/runtime assignment)))
  (is (= :organism-local (get-in assignment [:ao.worker/authority :memory])))
  (is (= :organism-local (get-in assignment [:ao.worker/authority :lifecycle])))
  (is (= :repository-local (get-in assignment [:ao.worker/authority :source])))
  (is (= :radicle-first (get-in assignment [:ao.worker/authority :issue]))))

(deftest admitted-intent-still-has-organism-gates
  (let [intent {:intent/id "intent-1"
                :intent/organization "etzhayyim"
                :intent/worker "ao:etzhayyim:tamaki"
                :intent/capability :intent/submit
                :intent/expires-at 2000}
        admitted (workplace/intent-decision assignment intent 1000)]
    (is (= :admitted (:intent/status admitted)))
    (is (= :not-executed (:intent/effect-status admitted)))
    (is (= [:incarnation-lease :capability :authority :homeostasis :hil]
           (:intent/next-gates admitted)))
    (is (= :organization-boundary
           (:intent/reason
            (workplace/intent-decision
             assignment (assoc intent :intent/organization "other") 1000))))
    (is (= :capability-not-granted
           (:intent/reason
            (workplace/intent-decision
             assignment (assoc intent :intent/capability :merge) 1000))))
    (is (= :intent-expired
           (:intent/reason
            (workplace/intent-decision assignment intent 2000))))))

(defn- write-envelope! [root envelope]
  (let [directory (workplace/inbox-directory root)
        file (io/file directory (str (:intent/id envelope) ".edn"))]
    (.mkdirs directory)
    (spit file (pr-str envelope))
    envelope))

(defn- envelope
  [id capability received-at payload]
  {:intent/id id
   :intent/organization "etzhayyim"
   :intent/worker "ao:etzhayyim:tamaki"
   :intent/capability capability
   :intent/issued-by "did:key:human"
   :intent/expires-at 5000
   :intent/received-at received-at
   :intent/payload-hash (str "sha256:" id)
   :intent/payload payload})

(defn- receipt [root id]
  (edn/read-string
   (slurp (io/file (workplace/receipt-directory root) (str id ".edn")))))

(deftest workplace-consumer-requires-approval-and-dispatches-once
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "tamaki-workplace"
               (make-array java.nio.file.attribute.FileAttribute 0)))
        calls (atom [])
        objective (envelope "intent-objective" :intent/submit 100
                            {:type "objective" :summary "Improve coverage"})
        reconcile
        (fn []
          (workplace/reconcile!
           {:state-root root
            :assignment assignment
            :now-ms 1000
            :gate-fn (constantly
                      {:admitted? true
                       :incarnation-lease :valid
                       :capability :granted
                       :authority :repository-local
                       :homeostasis :work
                       :hil :approved})
            :dispatch-fn
            (fn [intent]
              (swap! calls conj (:intent/id intent))
              {:agent.run/id "run-1"})}))]
    (write-envelope! root objective)
    (reconcile)
    (testing "admission alone cannot execute an effect"
      (is (empty? @calls))
      (is (= :awaiting-approval
             (:receipt/status (receipt root "intent-objective"))))
      (is (= :not-executed
             (:receipt/effect-status (receipt root "intent-objective")))))

    (write-envelope!
     root
     (assoc (envelope "intent-approval" :approval/submit 200
                      {:type "approval"
                       :decision :approved
                       :reference "intent-objective"})
            :intent/parent "intent-objective"))
    (reconcile)
    (reconcile)
    (testing "an approved parent reaches the injected effect boundary once"
      (is (= ["intent-objective"] @calls))
      (is (= :succeeded
             (:receipt/effect-status (receipt root "intent-objective"))))
      (is (= "run-1"
             (get-in (receipt root "intent-objective")
                     [:receipt/evidence :agent.run/id])))
      (is (= :succeeded
             (:receipt/effect-status (receipt root "intent-approval")))))
    (reconcile)
    (is (= ["intent-objective"] @calls))))

(deftest workplace-consumer-fails-closed-at-each-local-gate
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "tamaki-workplace-gates"
               (make-array java.nio.file.attribute.FileAttribute 0)))
        stop (envelope "intent-stop" :stop/request 100
                       {:type "stop" :summary "Governed stop"})
        calls (atom 0)]
    (write-envelope! root stop)
    (workplace/reconcile!
     {:state-root root
      :assignment (update assignment :ao.worker/capabilities conj :stop/request)
      :now-ms 1000
      :gate-fn (constantly {:admitted? false
                            :reason :homeostasis-unobserved})
      :dispatch-fn (fn [_] (swap! calls inc))})
    (is (zero? @calls))
    (is (= :deferred (:receipt/status (receipt root "intent-stop"))))
    (is (= :homeostasis-unobserved
           (:receipt/reason (receipt root "intent-stop"))))))
