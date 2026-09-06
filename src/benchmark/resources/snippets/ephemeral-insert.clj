(let [m {:a :v1 :b :v2}
      m2 (assoc m :c :v3)]
  (if (= (:a m2) :v1)
    (:c m2)
    nil))
