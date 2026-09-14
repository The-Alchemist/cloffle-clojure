(ns bench.snippet.event-enrich)

(defn bench []
    (let [event {:id "evt-101" :type :auth :user "alice" :tenant "org-1"
               :ip "127.0.0.1" :status :ok :timestamp "2026-09-06" :version :v1}
        enriched (assoc event :payload "ok")
        {:keys [id status user payload]} enriched]
    (if (and (= id "evt-101")
             (= status :ok)
             (= user "alice"))
      payload
      nil)))
