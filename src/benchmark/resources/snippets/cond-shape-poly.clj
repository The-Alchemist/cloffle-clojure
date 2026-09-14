(ns bench.snippet.cond-shape-poly)

(defn bench []
    (let [a true b true c false d true
        opts (-> {}
                 (cond-> a (assoc :alpha :va))
                 (cond-> b (assoc :beta :vb))
                 (cond-> c (assoc :gamma :vg))
                 (cond-> d (assoc :delta :vd)))
        v (get opts :alpha :none)]
    (if (and (= v :va)
             (= (get opts :beta :none) :vb)
             (= (get opts :gamma :none) :none)
             (= (get opts :delta :none) :vd))
      v
      nil)))
