(ns bench.snippet.rt-get-lookup)

(defn bench []
    (clojure.lang.RT/get {:a :v1 :b :v2 :c :v3} :b))
