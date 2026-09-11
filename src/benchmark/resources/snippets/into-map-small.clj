(let [[a b c d e] (into [] (map identity [:one :two :three :four :five]))]
  (if (= a :one)
    e
    nil))
