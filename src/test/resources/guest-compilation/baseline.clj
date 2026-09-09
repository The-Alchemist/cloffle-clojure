(ns test.guest.baseline)
(defn compute-pair [a b]
  (let [p [a b]]
    [(nth p 0) (nth p 1)
     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))
