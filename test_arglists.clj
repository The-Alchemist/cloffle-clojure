(require 'clojure.java.io)
(println "vec arglists:" (:arglists (meta (var clojure.core/vec))))
(println "vec arglists type:" (clojure.core/type (:arglists (meta (var clojure.core/vec)))))
(println "vec arglists first type:" (clojure.core/type (first (:arglists (meta (var clojure.core/vec))))))
