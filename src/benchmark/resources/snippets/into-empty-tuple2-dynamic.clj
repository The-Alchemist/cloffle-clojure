(let [from [:first :second]]
  (let [[a b] (into [] from)]
    (if (= a :first)
      b
      nil)))
