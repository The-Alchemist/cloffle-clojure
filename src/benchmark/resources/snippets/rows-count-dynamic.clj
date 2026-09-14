(ns bench.snippet.rows-count-dynamic)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (if (= (count rows) 2)
      :one
      nil)))
