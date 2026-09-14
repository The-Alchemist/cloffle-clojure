(ns bench.snippet.consume-conj-vector)

(defn bench []
    (let [v [:v1 :v2]] (peek (conj v :v3))))
