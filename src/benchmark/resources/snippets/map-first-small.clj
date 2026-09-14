(ns bench.snippet.map-first-small)

(defn bench []
    (first (map identity [:one :two :three :four :five])))
