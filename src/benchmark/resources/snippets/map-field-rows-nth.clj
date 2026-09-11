(let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
  (nth (map :id rows) 0))
