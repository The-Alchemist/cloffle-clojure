;; Hinted long locals with checked arithmetic.

(ns bench.snippet.prim-hinted-locals)

(defn run * [^long a ^long b]
    (clojure.lang.Numbers/add a (clojure.lang.Numbers/multiply b 3)))

(defn bench []
  (run 7 5))
