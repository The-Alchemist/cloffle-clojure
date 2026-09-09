(ns test.guest.event-enrich)
(defn guest-event-enrich-pipeline [payload-str]
  (let [event {:id 101 :type :auth :user "alice" :tenant "org-1"
               :ip "127.0.0.1" :status :ok :timestamp 1700000000 :version 1}
        enriched (assoc event :payload payload-str)
        {:keys [id status user payload]} enriched]
    (if (and (identical? id 101)
             (identical? status :ok)
             (identical? user "alice"))
      [payload (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]
      nil)))
