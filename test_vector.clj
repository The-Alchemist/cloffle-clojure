(def vector? (fn* [x] (instance? clojure.lang.IPersistentVector x)))
(println "result:" (vector? [1]))
