(do
  (defn step-3 [x]
    (when-not (string? x)
      (throw (RuntimeException. "expected string"))))
  (defn step-2 [x]
    (and x (step-3 x)))
  (defn step-1 [x]
    (or (nil? x) (step-2 x)))
  (step-1 42))
