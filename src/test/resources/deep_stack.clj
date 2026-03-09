(do
  (defn level-3 []
    (throw (Exception. "deep failure")))
  (defn level-2 []
    (level-3))
  (defn level-1 []
    (level-2))
  (level-1))
