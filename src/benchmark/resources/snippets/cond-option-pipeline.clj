(let [raw-timeout "500"
      opts (-> {}
               (cond-> true (assoc :id "btn"))
               (cond-> true (assoc :role "primary"))
               (cond-> true (assoc :href "/submit"))
               (cond-> raw-timeout (assoc :timeout raw-timeout)))
      {:keys [id role href timeout]} opts]
  (if (and (= id "btn")
           (= role "primary")
           (= href "/submit"))
    timeout
    nil))
