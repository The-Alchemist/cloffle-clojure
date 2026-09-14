(ns bench.snippet.nested-get-in)

(defn bench []
    (get-in {:user {:profile {:name "Alice"}}} [:user :profile :name]))
