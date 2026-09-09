(ns test.guest.assoc-pea)
(defn guest-ephemeral-promote8 [x]
  (let [m {:p0 0 :p1 1 :p2 2 :p3 3 :p4 4 :p5 5 :p6 6 :p7 7}
        m2 (assoc m :p8 x)]
    (if (identical? (:p0 m2) 0)
      [(:p8 m2) (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]
      nil)))
