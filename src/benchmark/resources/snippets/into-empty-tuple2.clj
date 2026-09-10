(let [[a b] (into [] [:first :second])]
  (if (= a :first)
    b
    nil))
