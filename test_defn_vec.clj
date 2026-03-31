(require '[clojure.spec.alpha :as s])
(require 'clojure.core.specs.alpha)
(println (s/valid? :clojure.core.specs.alpha/defn-args '(vec ([coll] 1))))
