;; Wave-11: coll-of, not-empty, empty coll defaults, mapv on maps (vals),
;; for comprehensions with :when/:while nil, doseq return nil, case on keywords,
;; assert does not run on false compile-time - skip assert,
;; vary-meta dissoc, namespace-munge, symbol parsing, reader conditional skip,
;; simple dot interop on String, compare strings, format basics.
;; Values only; throws record exception class only.

(defn- p [k v]
  (println (str k "\t" v)))

(defmacro probe [k & body]
  `(let [k# ~k]
     (try
       (p k# (pr-str (do ~@body)))
       (catch Throwable t#
         (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge11/coll?/nil" (coll? nil))
(probe "edge11/not-empty/nil" (not-empty nil))
(probe "edge11/not-empty/ev" (not-empty []))
(probe "edge11/not-empty/v" (not-empty [1]))

(probe "edge11/for/when-false"
       (vec (for [x [1 2 3] :when false] x)))
(probe "edge11/for/when-true"
       (vec (for [x [1 2 3] :when (pos? x)] x)))
(probe "edge11/for/while-nil"
       (vec (for [x nil :while true] x)))
(probe "edge11/doseq/ret" (doseq [x [1]] x))

(probe "edge11/case/kw" (case :a :a 1 :b 2 0))
(probe "edge11/case/kw-miss" (case :z :a 1 99))

(probe "edge11/vary-meta/dissoc"
       (meta (vary-meta (with-meta [1] {:a 1 :b 2}) dissoc :a)))

(probe "edge11/symbol/parse" [(symbol? 'foo/bar) (namespace 'foo/bar) (name 'foo/bar)])
(probe "edge11/keyword/parse" [(keyword? :foo/bar) (namespace :foo/bar)])

(probe "edge11/String/length" (.length ""))
(probe "edge11/String/isEmpty" (.isEmpty ""))
(probe "edge11/compare/str" (compare "a" "b"))

(probe "edge11/format/int" (format "%d" 42))
(probe "edge11/format/nil-arg" (format "%s" nil))

(probe "edge11/map/vals" (vec (vals {:b 2 :a 1})))
(probe "edge11/map/keys-sorted" (vec (sort (keys {:z 1 :a 2}))))

(probe "edge11/merge/left-pref" (merge {:a 1 :b 1} {:b 2}))
(probe "edge11/select-keys/nil" (select-keys nil [:a]))

(probe "edge11/update-vals/em" (update-vals {} (fn [x] (inc (or x 0)))))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
