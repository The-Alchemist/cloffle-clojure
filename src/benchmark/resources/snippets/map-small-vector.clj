(let [[a b c d e] (map identity [:one :two :three :four :five])]
  (if (= a :one)
    e
    nil))
