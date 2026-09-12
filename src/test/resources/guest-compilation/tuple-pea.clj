(ns test.guest.tuple-pea)

;; Guest analogue of TuplePeaBenchmark: PersistentTuple2 create + nth consume.
;; in-method stays in one CallTarget. via-helper / two-tuples-sum pass the tuple
;; across defn CallTargets so Truffle must inline before Graal PEA can virtualize it.

(defn consume-nth [t]
  (let [[a b] t]
    (+ a b)))

(defn make-tuple2 [x y]
  [x y])

(defn sum-tuples [t1 t2]
  (let [[a b] t1
        [c d] t2]
    [(+ a c) (+ b d)]))

(defn in-method [x y]
  (let [t [x y]]
    [(+ (nth t 0) (nth t 1))
     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))

(defn via-helper [x y]
  (let [t (make-tuple2 x y)]
    [(consume-nth t)
     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))

(defn two-tuples-sum [x y]
  (let [t1 (make-tuple2 x y)
        t2 (make-tuple2 y x)
        combined (sum-tuples t1 t2)]
    [(consume-nth combined)
     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))
