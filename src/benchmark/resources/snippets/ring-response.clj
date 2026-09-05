(let [resp {:status 200 :headers {:content-type "text/plain"} :body "ok"}
      resp2 (assoc resp :headers (assoc (:headers resp) :server "cloffle"))
      resp3 (assoc resp2 :status 201)
      {:keys [status headers body]} resp3]
  (if (and (= status 201)
           (= (:server headers) "cloffle")
           (= (:content-type headers) "text/plain"))
    body
    nil))
