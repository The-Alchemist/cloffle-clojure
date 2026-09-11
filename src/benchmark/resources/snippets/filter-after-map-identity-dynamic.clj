(let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
  (first (filter #(= :ok (:status %)) (map identity rows))))
