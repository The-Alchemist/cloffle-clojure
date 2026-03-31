(def vector? (fn* vector? [x] (clojure.core/instance? clojure.lang.IPersistentVector x)))
(println "is vector?" (vector? [1]))
