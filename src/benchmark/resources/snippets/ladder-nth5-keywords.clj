(let [x (nth [:one :two :three :four :five] 4)]
  (if (= x :five)
    x
    nil))
