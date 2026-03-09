(do
  (defn classify [x]
    (cond
      (< x 0)   "negative"
      (= x 0)   "zero"
      (> x 100) "big"))
  (classify 42))
