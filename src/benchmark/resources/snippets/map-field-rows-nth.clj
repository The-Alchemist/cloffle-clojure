(ns bench.snippet.map-field-rows-nth)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (nth (map :id rows) 0)))
