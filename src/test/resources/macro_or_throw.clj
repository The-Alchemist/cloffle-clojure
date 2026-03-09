(do
  (defn explode-or []
    (throw (RuntimeException. "kaboom in or")))
  (or false nil (explode-or)))
