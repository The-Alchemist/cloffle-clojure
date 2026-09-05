(let [m {:a 1 :b 3 :c 3}
      m2 (dissoc m :b)]
  (if (= (:a m2) 1)
    (:c m2)
    nil))
