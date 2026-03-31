(def vector? (fn* ^:static vector? [x] (instance? clojure.lang.IPersistentVector x)))
(def vec (fn* vec ([coll] (if (vector? coll) (if (instance? clojure.lang.IObj coll) (with-meta coll nil) (clojure.lang.LazilyPersistentVector/create coll)) (clojure.lang.LazilyPersistentVector/create coll)))))
(def defn (fn* defn [&form &env name & fdecl] (let [m (if (string? (first fdecl)) {:doc (first fdecl)} {}) fdecl (if (string? (first fdecl)) (next fdecl) fdecl) m (if (map? (first fdecl)) (conj m (first fdecl)) m) fdecl (if (map? (first fdecl)) (next fdecl) fdecl) fdecl (if (vector? (first fdecl)) (list fdecl) fdecl) m (if (map? (last fdecl)) (conj m (last fdecl)) m) fdecl (if (map? (last fdecl)) (butlast fdecl) fdecl) m (conj {:arglists (list (quote quote) fdecl)} m) m (conj (if (meta name) (meta name) {}) m)] (list (quote def) (with-meta name m) (with-meta (cons (quote fn*) fdecl) {:rettag (:tag m)})))))
(. (var defn) (setMacro))

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
                               (cons (vec (clojure.lang.RT/list '&form '&env args)) (next fd))))
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

