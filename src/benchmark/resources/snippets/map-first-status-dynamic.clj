(let [rows (vec '({:status :ok} {:status :fail}))]
  (first (map :status rows)))
