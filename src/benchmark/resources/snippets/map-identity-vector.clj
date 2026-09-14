(ns bench.snippet.map-identity-vector)

(defn bench []
    (first (map identity (vector :one :two :three :four :five))))
