;; Tight long loop/recur — allocation should be dominated by the final Long box, not per-iter boxing.

(ns bench.snippet.prim-long-loop)

(defn bench []
    (loop* [i 0]
    (if (clojure.lang.Numbers/lt i 100)
      (recur (clojure.lang.Numbers/unchecked_inc i))
      i)))
