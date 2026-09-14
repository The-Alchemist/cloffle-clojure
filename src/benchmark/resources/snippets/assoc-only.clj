(ns bench.snippet.assoc-only)

(defn bench []
    (let [m {:a :v1, :b :v2, :c :v3}]
    (assoc m :b :v999)))
