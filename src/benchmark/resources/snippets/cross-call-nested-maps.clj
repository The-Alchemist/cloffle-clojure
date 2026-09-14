(ns bench.snippet.cross-call-nested-maps)

(defn make []
    {:status :ok
                :headers {:content-type "text/plain"}
                :body "hello"})

(defn enrich [resp]
    (assoc resp :headers (assoc (:headers resp) :server "cloffle")))

(defn consume [resp]
    (let [{:keys [status headers body]} resp]
                    (if (and (= status :ok)
                             (= (:content-type headers) "text/plain")
                             (= (:server headers) "cloffle"))
                      body
                      nil)))

(defn bench []
  (consume (enrich (make))))
