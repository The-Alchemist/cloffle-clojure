(ns bench.snippet.into-empty-tuple2)

(defn bench []
    (let [[a b] (into [] [:first :second])]
    (if (= a :first)
      b
      nil)))
