(let [m {:a 1 :b 2}
      m2 (assoc m :c 3)]
  (if (= (:a m2) 1)
    (:c m2)
    nil))
