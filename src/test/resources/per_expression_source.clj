(do
  (defn fail []
    (throw (RuntimeException. "thrown from fail")))
  (defn inner []
    (fail))
  (defn outer []
    (inner))
  (outer))
