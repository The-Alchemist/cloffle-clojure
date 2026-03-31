(def defmacro (fn* defmacro [&form &env name & args]
             (let [prefix (loop [p (list name) args args]
                            (let [f (first args)]
                              (if (string? f)
                                (recur (cons f p) (next args))
                                (if (map? f)
                                  (recur (cons f p) (next args))
                                  p))))
                   fdecl (loop [fd args]
                           (if (string? (first fd))
                             (recur (next fd))
                             (if (map? (first fd))
                               (recur (next fd))
                               fd)))
                   fdecl (if (vector? (first fdecl))
                           (list fdecl)
                           fdecl)
                   add-implicit-args (fn* [fd]
                             (let [args (first fd)]
                               (cons (clojure.core/vec (clojure.core/cons (quote &form) (clojure.core/cons (quote &env) args))) (next fd))))
                   add-args (fn* add-args [acc ds]
                              (if (nil? ds)
                                acc
                                (let [d (first ds)]
                                  (if (map? d)
                                    (conj acc d)
                                    (recur (conj acc (add-implicit-args d)) (next ds))))))
                   fdecl (seq (add-args [] fdecl))
                   decl (loop [p prefix d fdecl]
                          (if p
                             (recur (next p) (cons (first p) d))
                             d))]
               (list 'do
                     (cons 'defn decl)
                     (list '. (list 'var name) '(setMacro))
                     (list 'var name)))))

(. (var defmacro) (setMacro))

(defmacro when
  [test & body]
  (list 'if test (cons 'do body)))
