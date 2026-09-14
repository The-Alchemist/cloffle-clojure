(ns bench.snippet.row-first-field-dynamic)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (let [x (:id (first rows))]
      (if (= x :one)
        x
        nil))))
