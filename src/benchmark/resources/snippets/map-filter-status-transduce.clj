(let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
  (first (into [] (comp (filter #(= :ok (:status %))) (map :id)) rows)))
