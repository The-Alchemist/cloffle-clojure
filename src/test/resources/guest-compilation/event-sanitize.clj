(ns test.guest.event-sanitize)
(defn guest-event-sanitize-pipeline [token]
  (let [event {:id 101 :user "alice" :secret token :temp 999 :status :ok}
        sanitized (-> event (dissoc :secret) (dissoc :temp))
        {:keys [id user secret temp status]} sanitized]
    (if (and (identical? id 101)
             (identical? status :ok)
             (identical? user "alice")
             (nil? secret)
             (nil? temp))
      [id (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]
      nil)))
