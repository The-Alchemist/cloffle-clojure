(let [event {:id 101 :user "alice" :secret "secret-token" :temp 999 :status :ok}
      sanitized (-> event (dissoc :secret) (dissoc :temp))
      {:keys [id user secret temp status]} sanitized]
  (if (and (= id 101)
           (= status :ok)
           (= user "alice")
           (nil? secret)
           (nil? temp))
    id
    nil))
