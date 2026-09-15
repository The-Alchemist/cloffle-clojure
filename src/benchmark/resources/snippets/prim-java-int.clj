(ns bench.snippet.prim-java-int)

(defn bench
  "Host int return must remain Integer at the Object boundary."
  []
  (Integer/parseInt "42"))
