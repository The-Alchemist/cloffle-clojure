(let [make (fn []
             {:status :ok
              :headers {:content-type "text/plain"}
              :body "hello"})
      enrich (fn [resp]
               (assoc resp :headers (assoc (:headers resp) :server "cloffle")))
      consume (fn [resp]
                (let [{:keys [status headers body]} resp]
                  (if (and (= status :ok)
                           (= (:content-type headers) "text/plain")
                           (= (:server headers) "cloffle"))
                    body
                    nil)))]
  (consume (enrich (make))))
