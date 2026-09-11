(let [rows [{:id :one} {:id :two} {:id :three} {:id :four} {:id :five}]]
  (nth (into [] (map :id rows)) 4))
