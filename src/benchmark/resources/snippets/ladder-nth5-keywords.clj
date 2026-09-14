(ns bench.snippet.ladder-nth5-keywords)

(defn bench []
    (let [x (nth [:one :two :three :four :five] 4)]
    (if (= x :five)
      x
      nil)))
