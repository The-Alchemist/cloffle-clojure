(loop [sum 0
       cnt 5]
  (if (= cnt 0)
    sum
    (recur (+ sum cnt)
           (dec cnt))))
