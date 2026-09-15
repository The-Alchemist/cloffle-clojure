(ns bench.snippet.prim-nth)

(defn bench
  "Compare-performance: vector literal for cross-leg parity (not multi-arg RT/vector interop).
  Measure RT/nth on a constant Indexed vector."
  []
  (let* [v [:a :b :c :d :e]]
    (clojure.lang.RT/nth v 3)))
