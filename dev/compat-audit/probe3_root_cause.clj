;; Root-cause confirmation for the print-dup tuple regression and the
;; with-redefs bypass.

(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k]
     (try (p k# (pr-str (do ~@body)))
          (catch Throwable t# (p k# (str "THREW " (.getName (class t#)) ": " (.getMessage t#)))))))

(def tuple-class (try (Class/forName "clojure.lang.PersistentTuple") (catch Throwable _ nil)))
(def concrete (class [1 2 3]))

;; --- print-dup dispatch resolution -----------------------------------------
;; Hypothesis: the defmethod on the abstract PersistentTuple never fires because
;; print-dup's existing prefer-method chain makes IPersistentCollection
;; transitively preferred over it (IPersistentCollection > java.util.Collection,
;; and java.util.Collection is an ancestor of PersistentTuple).
(probe "why/prefers-ipc-over-tuple"
       (boolean (and tuple-class
                     (prefers print-dup clojure.lang.IPersistentCollection tuple-class))))
(probe "why/prefers-tuple-over-ipc"
       (boolean (and tuple-class
                     (prefers print-dup tuple-class clojure.lang.IPersistentCollection))))
(probe "why/concrete-is-registered-dispatch-value"
       (contains? (methods print-dup) concrete))
(probe "why/collection-is-ancestor-of-tuple"
       (boolean (and tuple-class (isa? tuple-class java.util.Collection))))
(probe "why/ipc-prefer-table"
       (vec (sort (map #(.getName ^Class %)
                       (get (prefers print-dup) clojure.lang.IPersistentCollection)))))

;; Fix A: register the defmethod on the concrete class instead of the abstract base.
(probe "fix/exact-class-defmethod"
       (do (defmethod print-dup concrete [o w] (print-method o w))
           (let [out (binding [*print-dup* true] (pr-str [1 2 3]))]
             (remove-method print-dup concrete)
             out)))

;; Fix B: add an explicit preference for the tuple base over the generic methods.
(probe "fix/prefer-method"
       (when tuple-class
         (prefer-method print-dup tuple-class clojure.lang.IPersistentCollection)
         (prefer-method print-dup tuple-class java.util.Collection)
         (binding [*print-dup* true] (pr-str [1 2 3]))))
(probe "fix/prefer-method-roundtrip"
       (binding [*print-dup* true] (= [1 2 3] (read-string (pr-str [1 2 3])))))
(probe "fix/prefer-method-nested"
       (binding [*print-dup* true] (pr-str {:a [1 2]})))

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
