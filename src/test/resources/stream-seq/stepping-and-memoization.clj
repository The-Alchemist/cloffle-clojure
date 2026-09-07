(let [c (atom 0)
      s (->> (range 10)
             (filter even?)
             (map (fn [x] (swap! c inc) (inc x))))]
  (assert (= (first s) 1))
  (assert (= (first s) 1))
  (assert (= @c 1))
  (let [s2 (next s)]
    (assert (= (first s2) 3))
    (assert (= (first s2) 3))
    (assert (= @c 2))
    (assert (= (first (next s2)) 5))
    (assert (= @c 3))
    ;; Re-traverse s from head
    (assert (= (first s) 1))
    (assert (= (first (next s)) 3))
    (assert (= @c 3))
    @c))
