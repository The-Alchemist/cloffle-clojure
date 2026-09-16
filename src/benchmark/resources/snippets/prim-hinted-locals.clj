(ns bench.snippet.prim-hinted-locals)

(defn run [^long a ^long b]
  (clojure.lang.Numbers/add a (clojure.lang.Numbers/multiply b 3)))

(defn bench
  "Hinted long locals with checked arithmetic."
  []
  (run 7 5))
