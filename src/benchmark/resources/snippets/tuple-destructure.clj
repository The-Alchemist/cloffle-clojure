(ns bench.snippet.tuple-destructure)

(defn bench []
    (let [[a b] [:first :second]]
    (if (= a :first)
      b
      nil)))
