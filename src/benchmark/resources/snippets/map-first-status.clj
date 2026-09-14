(ns bench.snippet.map-first-status)

(defn bench []
    (first (map :status [{:status :ok :id 1} {:status :fail :id 2}])))
