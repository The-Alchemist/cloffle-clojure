(ns bench.snippet.cross-call-defn-pipeline)

(defn make-request []
    {:uri "/api/data"
     :request-method :post
     :headers {:content-type "application/json"}
     :body "payload"})

(defn add-params [req]
    (assoc req :params {:query "search" :limit 10}))

(defn add-session [req]
    (assoc req :session {:user "alice" :role :admin}))

(defn stamp-headers [req]
    (assoc req :headers (assoc (:headers req) :server "cloffle")))

(defn handle-request [req]
    (let [{:keys [request-method headers params session body]} req]
      (if (and (= request-method :post)
               (= (:user session) "alice")
               (= (:role session) :admin)
               (= (:query params) "search")
               (= (:content-type headers) "application/json")
               (= (:server headers) "cloffle"))
        body
        nil)))

(defn bench []
  (handle-request (stamp-headers (add-session (add-params (make-request))))))
