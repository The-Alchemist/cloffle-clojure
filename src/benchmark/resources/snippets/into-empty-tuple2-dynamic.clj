(ns bench.snippet.into-empty-tuple2-dynamic)

(defn bench []
    (let [from [:first :second]]
    (let [[a b] (into [] from)]
      (if (= a :first)
        b
        nil))))
