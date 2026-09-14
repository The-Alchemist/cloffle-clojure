(ns bench.snippet.map-field-rows-runtime)

(defn bench []
    (let [coll (list {:status :ok :id :one} {:status :fail :id :two})
        rows (vec coll)]
    (first (map :id rows))))
