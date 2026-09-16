(ns bench.snippet.identical-nil-dynamic)

(defn bench []
  ;; *print-level* and *print-length* are dynamic vars
  (let [x *print-level*
        y *print-length*]
    (+ (if (identical? x x) 1 0)
       (if (identical? x y) 0 2)
       (if (nil? x) 4 0)
       (if (nil? y) 8 0))))
