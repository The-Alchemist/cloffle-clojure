(ns bench.snippet.array-map-lookup)

(defn bench []
    (get {:a :v1 :b :v2 :c :v3} :b))
