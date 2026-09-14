(ns bench.snippet.middleware-pipeline)

(defn bench []
    (let [req {:uri "/api/data" :request-method :post :headers {:content-type "application/json"} :body "test-payload"}
        req2 (assoc req :params {:query "search"})
        req3 (assoc req2 :session {:user "alice"})
        {:keys [uri request-method headers params session body]} req3]
    (if (and (= request-method :post)
             (= (:user session) "alice")
             (= (:query params) "search")
             (= (:content-type headers) "application/json"))
      body
      nil)))
