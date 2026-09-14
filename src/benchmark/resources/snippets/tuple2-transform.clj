(ns bench.snippet.tuple2-transform)

(defn bench []
    (let [[a b] [:first :second]
        [c d] [b a]]
    c))
