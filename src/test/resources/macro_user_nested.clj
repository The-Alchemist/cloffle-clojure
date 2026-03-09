(do
  (defmacro check-positive! [x]
    `(when-not (pos? ~x)
       (throw (RuntimeException.
         (str "must be positive, got " ~x)))))
  (defn process [n]
    (check-positive! n)
    (* n n))
  (process -5))
