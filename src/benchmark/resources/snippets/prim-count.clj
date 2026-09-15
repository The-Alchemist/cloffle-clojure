(ns bench.snippet.prim-count)

(defn bench
  "Compare-performance: use a vector literal so stock Clojure and Cloffle compile the same source.
  Measure RT/count on a constant Indexed vector (host static call / Object boundary)."
  []
  (clojure.lang.RT/count [:a :b :c :d]))
