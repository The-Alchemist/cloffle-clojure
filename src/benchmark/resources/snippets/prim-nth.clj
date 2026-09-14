;; RT.vector is varargs Object... — do not call (RT/vector a b c …) with multi-arity args.
;; Measure RT/nth on a constant Indexed vector.

(ns bench.snippet.prim-nth)

(defn bench []
    (let* [v [:a :b :c :d :e]]
    (clojure.lang.RT/nth v 3)))
