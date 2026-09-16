(ns test.guest.merge-lowering)

(defn merge-literal [m]
  (:status (merge m {:status 200 :ok true})))

(defn merge-runtime [a b]
  ;; Latter map wins on conflict: (:role (merge {:role :admin} {:role :user})) => :user
  (name (:role (merge a b))))

(defn merge-nil-left [b]
  (:a (merge nil b)))
