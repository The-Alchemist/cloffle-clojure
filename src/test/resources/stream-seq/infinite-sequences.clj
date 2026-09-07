(= (into [] (take 3 (filter even? (iterate inc 0))))
   [0 2 4])
