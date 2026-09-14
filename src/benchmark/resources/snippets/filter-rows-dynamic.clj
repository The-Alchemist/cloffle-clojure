(ns bench.snippet.filter-rows-dynamic)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (first (filter #(= :ok (:status %)) rows))))
