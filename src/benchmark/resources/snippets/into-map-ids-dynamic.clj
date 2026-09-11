(let [rows (vec '({:id :one} {:id :two} {:id :three} {:id :four} {:id :five}))]
  (let [[a b c d e] (into [] (map :id rows))]
    (if (= a :one)
      e
      nil)))
