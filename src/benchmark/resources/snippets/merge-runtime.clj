(ns bench.snippet.merge-runtime)

(def ^:private defaults {:role :user :active true})

(defn bench []
  (let [req {:id 1 :role :admin}]
    ;; Latter wins: defaults override :role
    (:role (merge req defaults))))
