(ns bench.snippet.ephemeral-promote8)

(defn bench []
    (let [m {:p0 :v0 :p1 :v1 :p2 :v2 :p3 :v3 :p4 :v4 :p5 :v5 :p6 :v6 :p7 :v7}
        m2 (assoc m :p8 :v8)]
    (if (= (:p0 m2) :v0)
      (:p8 m2)
      nil)))
