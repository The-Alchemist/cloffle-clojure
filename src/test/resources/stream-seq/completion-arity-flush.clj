(let [s (clojure.lang.StreamSeq. (partition-all 2) [1 2 3])]
  ;; partition-all flushes the remaining [3] on completion arity
  (= (reduce conj [] s) [[1 2] [3]]))
