(let [ids (map :id (vector {:id :one :n 1} {:id :two :n 2} {:id :three :n 3}
                           {:id :four :n 4} {:id :five :n 5}))]
  (if (= (first ids) :one)
    (nth ids 4)
    nil))
