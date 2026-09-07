(let [src (range 10)
      s (->> src
             (filter even?)
             (map inc)
             (take 3))]
  (identical? (.-source ^clojure.lang.StreamSeq s) src))
