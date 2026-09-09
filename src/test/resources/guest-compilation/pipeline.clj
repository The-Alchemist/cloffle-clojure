(ns test.guest.pipeline)
(defn thread-and-destructure [v1 v2]
  (let [m (-> {:a 10 :b 20}
              (assoc :a v1)
              (assoc :b v2))
        [a b] [(:a m) (:b m)]]
    [a b (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))
