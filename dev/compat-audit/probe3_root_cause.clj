;; with-redefs / alter-var-root bypass: does a lowered call site still honour Var rebinding?

(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k]
     (try (p k# (pr-str (do ~@body)))
          (catch Throwable t# (p k# (str "THREW " (.getName (class t#)) ": " (.getMessage t#)))))))

;; --- with-redefs bypass: is the Var actually rebound? -----------------------
;; If the Var's root/thread binding changes but the call site still returns the
;; original result, the call was lowered to an intrinsic and skipped the Var.
(probe "bypass/first-var-value-during-redef"
       (with-redefs [first (fn [& _] :redefined)]
         [(first [1 2 3])                    ; direct call site
          (@#'clojure.core/first [1 2 3])    ; explicit Var deref
          ((var-get #'first) [1 2 3])]))
(probe "bypass/assoc-var-value-during-redef"
       (with-redefs [assoc (fn [& _] :redefined)]
         [(assoc {} :a 1) (@#'clojure.core/assoc {} :a 1)]))
(probe "bypass/str-var-value-during-redef"
       (with-redefs [str (fn [& _] :redefined)]
         [(str "a" "b") (@#'clojure.core/str "a" "b")]))
;; Contrast with one that does honour the redefinition.
(probe "bypass/count-var-value-during-redef"
       (with-redefs [count (fn [& _] :redefined)]
         [(count [1 2 3]) (@#'clojure.core/count [1 2 3])]))

;; alter-var-root is the other common mechanism (mocking libraries, tracing).
(probe "bypass/alter-var-root-first"
       (let [orig first]
         (alter-var-root #'first (constantly (fn [& _] :altered)))
         (let [r [(first [1 2 3]) (@#'clojure.core/first [1 2 3])]]
           (alter-var-root #'first (constantly orig))
           r)))

;; Does the bypass also defeat clojure.core/with-redefs-fn and trace-style wrapping?
(probe "bypass/with-redefs-fn-first"
       (with-redefs-fn {#'first (fn [& _] :redefined)}
         (fn [] (first [1 2 3]))))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
