(println (map (fn [m] [(.reqParms m) (.restParm m)]) (.methods (clojure.lang.Compiler/analyze clojure.lang.Compiler$C/EVAL '(fn ([] 1) ([x & ys] 3) ([x] 2))))))
