;; Host int return must remain Integer at the Object boundary.

(ns bench.snippet.prim-java-int)

(defn bench []
    (Integer/parseInt "42"))
