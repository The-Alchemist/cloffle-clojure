;; Hinted long locals with checked arithmetic.
((fn* [^long a ^long b]
   (clojure.lang.Numbers/add a (clojure.lang.Numbers/multiply b 3)))
 7 5)
