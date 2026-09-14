(ns bench.snippet.ladder-seq-first5-keywords)

(defn bench []
    (let [x (first (seq [:one :two :three :four :five]))]
    (if (= x :one)
      x
      nil)))
