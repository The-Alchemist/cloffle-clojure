(let [m {:a "initial" :b :v2 :c :v3}]
  (:a (assoc m :a "replacement")))
