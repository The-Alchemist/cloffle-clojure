(let [[a b c d e] (mapv identity [:one :two :three :four :five])]
  (if (= a :one)
    e
    nil))
