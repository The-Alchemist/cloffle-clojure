(ns bench.snippet.consume-assoc-no-let)

(defn bench []
    (:a (assoc {:a :v1, :b :v2, :c :v3} :b :v999)))
