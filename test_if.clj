(println "if evaluates to:" (if nil nil {}))
(println "conj evaluates to:" (clojure.core/type (conj (if nil nil {}) {})))
