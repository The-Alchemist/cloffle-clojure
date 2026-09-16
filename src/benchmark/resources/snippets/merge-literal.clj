(ns bench.snippet.merge-literal)

(defn bench []
  (let [m {:a 1 :b 2 :c 3}]
    (:status (merge m {:status 200 :ok true}))))
