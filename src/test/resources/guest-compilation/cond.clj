(ns test.guest.cond)
(defn guest-cond-options [id cls href timeout]
  (let [opts (cond-> {}
               id (assoc :id id)
               cls (assoc :class cls)
               href (assoc :href href)
               timeout (assoc :timeout timeout))
        {:keys [id class href timeout]} opts]
    (if (and (identical? id "btn")
             (identical? class "primary")
             (identical? href "/submit"))
      [timeout (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]
      nil)))
