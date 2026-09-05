(let [m {:p0 0 :p1 1 :p2 2 :p3 3 :p4 4 :p5 5 :p6 6 :p7 7}
      m2 (assoc m :p8 3)]
  (if (= (:p0 m2) 0)
    (:p8 m2)
    nil))
