(let [m2 (assoc {:a :v1, :b :v2, :c :v3} :b :v999)]
  (when (= (:b m2) :v999)
    nil))
