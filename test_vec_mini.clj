(def vector? (fn* ^:static vector? [x] (instance? clojure.lang.IPersistentVector x)))
(def vec (fn* vec ([coll] (if (vector? coll) (if (instance? clojure.lang.IObj coll) (with-meta coll nil) (clojure.lang.LazilyPersistentVector/create coll)) (clojure.lang.LazilyPersistentVector/create coll)))))
(println "vec test:" (clojure.core/type (vec (clojure.lang.RT/list '&form '&env 'test '& 'body))))
