(ns bench.snippet.into-map-ids)

(defn bench []
    (let [[a b c d e] (into [] (map :id [{:id :one} {:id :two} {:id :three} {:id :four} {:id :five}]))]
    (if (= a :one)
      e
      nil)))
