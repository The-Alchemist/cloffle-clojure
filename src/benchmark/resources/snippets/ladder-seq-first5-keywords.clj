(let [x (first (seq [:one :two :three :four :five]))]
  (if (= x :one)
    x
    nil))
