(def symbol? (fn* [x] (instance? clojure.lang.Symbol x)))
(println "result:" (symbol? 'x))
