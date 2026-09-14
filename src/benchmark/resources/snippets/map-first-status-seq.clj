(ns bench.snippet.map-first-status-seq)

(defn bench []
    (first (map :status '({:status :ok} {:status :fail}))))
