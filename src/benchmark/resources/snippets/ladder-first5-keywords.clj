(ns bench.snippet.ladder-first5-keywords)

(defn bench []
    (let [x (first [:one :two :three :four :five])]
    (if (= x :one)
      x
      nil)))
