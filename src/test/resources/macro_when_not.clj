(do
  (defn check-age [age]
    (when-not (>= age 21)
      (throw (IllegalArgumentException. "cannot drink"))))
  (check-age 12))
