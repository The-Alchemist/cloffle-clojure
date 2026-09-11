(let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
  (first (map :id (seq rows))))
