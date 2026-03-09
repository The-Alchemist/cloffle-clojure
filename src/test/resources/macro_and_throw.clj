(do
  (defn explode []
    (throw (RuntimeException. "kaboom in and")))
  (and true (explode) false))
