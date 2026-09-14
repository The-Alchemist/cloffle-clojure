(ns bench.snippet.map-first-status-list)

(defn bench []
    (first (map :status [{:status :ok} {:status :fail}])))
