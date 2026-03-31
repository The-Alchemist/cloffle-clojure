(def f (fn* [& args] (println (clojure.core/type args))))
(f 1 2)
