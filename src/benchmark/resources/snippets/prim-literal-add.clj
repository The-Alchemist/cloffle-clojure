;; Hot long+long through Numbers; Object boundary must still hand back Long.

(ns bench.snippet.prim-literal-add)

(defn bench []
    (clojure.lang.Numbers/add 1 2))
