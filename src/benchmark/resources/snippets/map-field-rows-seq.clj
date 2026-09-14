(ns bench.snippet.map-field-rows-seq)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (first (map :id (seq rows)))))
