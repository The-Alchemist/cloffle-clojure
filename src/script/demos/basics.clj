;; Simple expressions
(+ 1 2)

;; Multi-line let
(let [x 10
      y 20]
  (+ x y))

;; Define and call
(do
  (defn square [n]
    (* n n))
  (square 7))

;; Nested conditionals
(if (< 1 2)
  (if (> 3 4)
    "both"
    "only-first")
  "neither")

;; Loop/recur
(loop [sum 0
       cnt 5]
  (if (= cnt 0)
    sum
    (recur (+ sum cnt)
           (dec cnt))))
