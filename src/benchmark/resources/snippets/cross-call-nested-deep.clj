(ns bench.snippet.cross-call-nested-deep)

(defn make []
    {:user {:account {:profile {:name "Alice" :role :admin}
                                 :prefs {:theme :dark :locale "en-US"}}
                       :session {:token "t1" :ttl :hour}}})

(defn wrap [doc]
    (assoc doc :envelope {:ok true :via :api}))

(defn enrich [doc]
    (let [user (:user doc)
                       account (:account user)
                       profile (assoc (:profile account) :role :owner)]
                   (assoc doc :user (assoc user :account (assoc account :profile profile)))))

(defn consume [doc]
    (let [account (:account (:user doc))
                        profile (:profile account)
                        prefs (:prefs account)
                        env (:envelope doc)]
                    (if (and (= (:role profile) :owner)
                             (= (:theme prefs) :dark)
                             (= (:ok env) true)
                             (= (:via env) :api))
                      (:name profile)
                      nil)))

(defn bench []
  (consume (enrich (wrap (make)))))
