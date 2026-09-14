(ns bench.snippet.conj-chain)

(defn bench []
    (let [v (conj (conj (conj [] :v1) :v2) :v3)]
    (if (= (peek v) :v3)
      (first v)
      nil)))
