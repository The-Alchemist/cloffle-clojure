(let [s (clojure.lang.StreamSeq. (map inc) [1 2 3])
      binary-rf (fn [acc x] (conj acc x))]
  (= (reduce binary-rf [] s) [2 3 4]))
