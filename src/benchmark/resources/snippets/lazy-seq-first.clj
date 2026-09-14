(ns bench.snippet.lazy-seq-first)

(defn bench []
    (first (lazy-seq [:first])))
