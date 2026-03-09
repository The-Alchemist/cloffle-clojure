(do
  (defn kaboom []
    (throw (RuntimeException. "something went wrong")))
  (defn call-kaboom []
    (kaboom))
  (call-kaboom))
