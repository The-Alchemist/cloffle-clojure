(require '[clojure.spec.alpha :as s])
(require 'clojure.core.specs.alpha)
(println "spec conform vec with &form:" (s/conform :clojure.core.specs.alpha/defn-args '(vec (cons '&form (cons '&env [])))))
