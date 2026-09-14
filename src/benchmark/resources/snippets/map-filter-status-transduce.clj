(ns bench.snippet.map-filter-status-transduce)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (first (into [] (comp (map :id) (filter #(= :ok (:status %)))) rows))))
