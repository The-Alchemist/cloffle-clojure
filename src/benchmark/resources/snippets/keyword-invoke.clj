(ns bench.snippet.keyword-invoke)

(defn bench []
    (:b {:a :v1 :b :v2 :c :v3}))
