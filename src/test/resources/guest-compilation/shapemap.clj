(ns test.guest.shapemap)
(defn assoc-and-lookup [v]
  (let [m {:a 1 :b 2 :c 3}
        updated (assoc m :a v)]
    [(:a updated)
     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))
