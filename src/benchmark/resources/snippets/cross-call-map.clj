(ns bench.snippet.cross-call-map)

(defn run [s h b]
    (let [resp {:status s :headers h :body b}
           {:keys [status headers body]} resp]
       (if (and (= status :ok)
                (= (:content-type headers) "text/plain"))
         body
         nil)))

(defn bench []
  (run :ok {:content-type "text/plain"} "hello"))
