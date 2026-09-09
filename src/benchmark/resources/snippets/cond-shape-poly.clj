(let [a true b true c false d true
      opts (-> {}
               (cond-> a (assoc :alpha 1))
               (cond-> b (assoc :beta 2))
               (cond-> c (assoc :gamma 3))
               (cond-> d (assoc :delta 4)))
      v (get opts :alpha 0)]
  (+ v (get opts :beta 0) (get opts :delta 0)))
