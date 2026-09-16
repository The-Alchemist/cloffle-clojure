(ns bench.snippet.assoc-multi-arity)

(defn bench []
  (let [m {:a :v1 :b :v2 :c :v3}]
    (assoc m :a 1 :b 2)))
