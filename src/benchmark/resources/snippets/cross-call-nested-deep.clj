(let [make (fn []
             {:user {:account {:profile {:name "Alice" :role :admin}
                               :prefs {:theme :dark :locale "en-US"}}
                     :session {:token "t1" :ttl :hour}}})
      wrap (fn [doc]
             (assoc doc :envelope {:ok true :via :api}))
      enrich (fn [doc]
               (let [user (:user doc)
                     account (:account user)
                     profile (assoc (:profile account) :role :owner)]
                 (assoc doc :user (assoc user :account (assoc account :profile profile)))))
      consume (fn [doc]
                (let [account (:account (:user doc))
                      profile (:profile account)
                      prefs (:prefs account)
                      env (:envelope doc)]
                  (if (and (= (:role profile) :owner)
                           (= (:theme prefs) :dark)
                           (= (:ok env) true)
                           (= (:via env) :api))
                    (:name profile)
                    nil)))]
  (consume (enrich (wrap (make)))))
