(let [[a b] [:first :second]]
  (if (= a :first)
    b
    nil))
