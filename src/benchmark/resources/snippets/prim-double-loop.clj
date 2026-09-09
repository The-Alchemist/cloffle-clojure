(loop* [i 0.0]
  (if (clojure.lang.Numbers/lt i 100.0)
    (recur (clojure.lang.Numbers/add i 1.0))
    i))
