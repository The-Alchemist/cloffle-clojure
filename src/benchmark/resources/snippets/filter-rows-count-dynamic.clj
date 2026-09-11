(let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
  (if (= (count (filter #(= :ok (:status %)) rows)) 1)
    :one
    nil))
