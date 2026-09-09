(ns test.guest.dissoc-pea)
(defn guest-ephemeral-dissoc [x]
  (let [m {:a 1 :b x :c 3}
        m2 (dissoc m :b)]
    (if (identical? (:a m2) 1)
      [(:c m2) (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]
      nil)))
