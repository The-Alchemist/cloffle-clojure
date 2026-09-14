(ns bench.snippet.ephemeral-dissoc)

(defn bench []
    (let [m {:a :v1 :b :v3 :c :v3}
        m2 (dissoc m :b)]
    (if (= (:a m2) :v1)
      (:c m2)
      nil)))
