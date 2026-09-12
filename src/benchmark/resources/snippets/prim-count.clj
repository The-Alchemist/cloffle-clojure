;; RT.vector is varargs Object... — (RT/vector a b c d) does not resolve (exact arity 4).
;; Measure RT/count on a constant vector (host static call / Object boundary).
(clojure.lang.RT/count [:a :b :c :d])
