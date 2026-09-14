(ns bench.snippet.cross-call-nested-large)

(defn make []
    {:id "user-101"
                :type :user
                :tenant-id "org-3"
                :email "avery@example.test"
                :username "avery"
                :status :pending
                :role :admin
                :created-at "2026-01-10"
                :updated-at "2026-09-09"
                :version "v7"
                :locale "en-US"
                :profile {:display-name "Avery" :given-name "Avery" :family-name "Nguyen" :avatar "/avatars/101"}
                :settings {:theme :dark :digest :daily :notifications :enabled :date-format "yyyy-MM-dd"}
                :headers {:content-type "application/json" :accept "application/json"}
                :roles [:admin :clinician :reader :writer :auditor :guest :member]
                :path ["api" "v1" "users" "user-101"]})

(defn enrich [entity]
    (let [headers (assoc (:headers entity) :server "cloffle")
                       roles (conj (:roles entity) :owner)
                       profile (assoc (:profile entity) :display-name "Avery Nguyen")]
                   (-> entity
                       (assoc :status :active)
                       (assoc :headers headers)
                       (assoc :roles roles)
                       (assoc :profile profile))))

(defn consume [entity]
    (let [{:keys [status email profile headers roles path]} entity
                        [r0 r1 r2 r3 r4 r5 r6 r7] roles
                        [p0 p1 p2 p3] path]
                    (if (and (= status :active)
                             (= (:server headers) "cloffle")
                             (= (:content-type headers) "application/json")
                             (= (:display-name profile) "Avery Nguyen")
                             (= r0 :admin)
                             (= r7 :owner)
                             (= p0 "api")
                             (= p3 "user-101"))
                      email
                      nil)))

(defn bench []
  (consume (enrich (make))))
