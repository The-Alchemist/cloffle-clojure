(let [s (->> (range 10)
             (filter even?)
             (map inc)
             (take 3))]
  (instance? clojure.lang.StreamSeq s))
