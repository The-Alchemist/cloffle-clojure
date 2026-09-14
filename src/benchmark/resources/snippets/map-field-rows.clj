(ns bench.snippet.map-field-rows)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (first (map :id rows))))
