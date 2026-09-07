(let [c (atom 0)
      s (clojure.lang.StreamSeq. (map (fn [x] (swap! c inc) (* x 10))) [1 2 3])]
  (assert (= (first s) 10))
  (assert (realized? s))
  (let [sum (reduce + 0 s)]
    (assert (= sum 60))
    ;; Because _spine is realized, reduce iterates over cached spine without re-invoking source
    (= @c 3)))
