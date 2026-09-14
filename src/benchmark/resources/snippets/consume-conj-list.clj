(ns bench.snippet.consume-conj-list)

(defn bench []
    (let [l (list :v2 :v3)] (first (conj l :v1))))
