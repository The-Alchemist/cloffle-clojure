(let [m {:a "initial" :b 2 :c 3}]
  (:a (assoc m :a "replacement")))
