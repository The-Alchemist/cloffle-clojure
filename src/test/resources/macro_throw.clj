(do
  (defn validate [x]
    (when-not (pos? x)
      (throw (RuntimeException. "must be positive"))))
  (validate -1))
