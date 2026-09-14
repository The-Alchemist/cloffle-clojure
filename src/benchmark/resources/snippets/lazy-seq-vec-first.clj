(ns bench.snippet.lazy-seq-vec-first)

(defn bench []
    (first (lazy-seq [:first])))
