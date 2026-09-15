(ns bench.snippet.prim-object-boundary)

(defn bench
  "Forces a Long box into a collection then reads it back."
  []
  (clojure.lang.RT/nth
   (clojure.lang.RT/conj clojure.lang.PersistentVector/EMPTY
                         (clojure.lang.Numbers/add 10 20))
   0))
