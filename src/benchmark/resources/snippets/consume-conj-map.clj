(ns bench.snippet.consume-conj-map)

(defn bench []
    (let [m {:a :v1 :b :v2}] (:c (conj m {:c :v3}))))
