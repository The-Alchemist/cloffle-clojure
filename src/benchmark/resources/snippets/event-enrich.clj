(let [event {:id 101 :type :auth :user "alice" :tenant "org-1"
             :ip "127.0.0.1" :status :ok :timestamp 1700000000 :version 1}
      enriched (assoc event :payload "ok")
      {:keys [id status user payload]} enriched]
  (if (and (= id 101)
           (= status :ok)
           (= user "alice"))
    payload
    nil))
