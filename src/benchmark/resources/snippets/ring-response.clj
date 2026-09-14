(ns bench.snippet.ring-response)

(defn bench []
    (let [resp {:status :ok :headers {:content-type "text/plain"} :body "ok"}
        resp2 (assoc resp :headers (assoc (:headers resp) :server "cloffle"))
        resp3 (assoc resp2 :status :created)
        {:keys [status headers body]} resp3]
    (if (and (= status :created)
             (= (:server headers) "cloffle")
             (= (:content-type headers) "text/plain"))
      body
      nil)))
