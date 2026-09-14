(ns bench.snippet.map-first-one)

(defn bench []
    (first (map identity [:one])))
