(do
  (defn check-age [age]
    (when-not (>= age 18)
      (throw (IllegalArgumentException. "too young"))))
  (check-age 12))
