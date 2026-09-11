(let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
  (first (filter #(= :one %) (map :id rows))))
