(ns bench.snippet.map-first-status-dynamic)

(defn bench []
    (let [rows (vec '({:status :ok} {:status :fail}))]
    (first (map :status rows))))
