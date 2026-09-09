;; Differential probe: alter-var-root, thread bindings, remove-method.
;; Same file under stock Clojure 1.12 and Cloffle. Prints key<TAB>value.
;;
;; Emission and cleanup are host-only so redefining seq/first/next/nth cannot
;; poison later cases or silently corrupt the report.

(defn- emit
  [^String k ^String v]
  (.println System/out (.concat (.concat k "\t") v)))

(defn- throw-label
  [^Throwable t]
  (.concat (.concat "THREW " (.getName (class t)))
           (.concat ": " (let [m (.getMessage t)] (if (nil? m) "" m)))))

(defn- val-label
  [x]
  (cond (nil? x) "nil"
        (true? x) "true"
        (false? x) "false"
        :else (.toString x)))

(defmacro probe
  [k & body]
  `(let [k# ~k]
     (try
       (emit k# (val-label (do ~@body)))
       (catch Throwable t#
         (emit k# (throw-label t#))))))

(defn- fresh-var
  [root dynamic?]
  (let [ns (clojure.lang.Namespace/findOrCreate
            (clojure.lang.Symbol/intern nil (.concat "probe.var." (str (System/nanoTime)))))
        v (clojure.lang.Var/intern ns (clojure.lang.Symbol/intern nil "x") root)]
    (when dynamic?
      (.setDynamic v))
    v))

(defn- bind-root!
  [^clojure.lang.Var v val]
  (.bindRoot v val))

(defn- core-seq-vars
  []
  (doto (java.util.ArrayList.)
    (.add #'clojure.core/seq)
    (.add #'clojure.core/first)
    (.add #'clojure.core/next)
    (.add #'clojure.core/nth)))

(defn- mock-redefined
  [& _]
  :redefined)

;; ---------------------------------------------------------------------------
;; alter-var-root
;; ---------------------------------------------------------------------------

(probe "alter/args-and-return"
       (let [v (fresh-var 10 false)
             seen (atom nil)
             ret (alter-var-root v (fn [old a b]
                                     (reset! seen [old a b])
                                     (+ old a b))
                                 1 5)]
         (and (= ret 16) (= 16 @v) (= [10 1 5] @seen))))

(probe "alter/throw-leaves-raw-root"
       (let [root (Object.)
             v (fresh-var root false)
             threw (try
                     (alter-var-root v (fn [_] (throw (ex-info "nope" {}))))
                     false
                     (catch Exception _ true))]
         (and threw (identical? root (.getRawRoot v)))))

(probe "alter/validator-rejects-without-watch"
       (let [v (fresh-var 0 false)
             fires (atom 0)]
         (.setValidator v even?)
         (add-watch v :w (fn [& _] (swap! fires inc)))
         (let [threw (try
                       (alter-var-root v (constantly 1))
                       false
                       (catch Exception _ true))]
           (and threw (= 0 (.getRawRoot v)) (zero? @fires)))))

(probe "alter/watch-once"
       (let [v (fresh-var :old false)
             seen (atom nil)]
         (add-watch v :w (fn [_ _ o n] (reset! seen [o n])))
         (let [ret (alter-var-root v (constantly :new))]
           (and (= ret :new) (= [:old :new] @seen) (= :new (.getRawRoot v))))))

(probe "alter/while-bound-keeps-thread-value"
       (let [v (fresh-var :root true)]
         (clojure.lang.Var/pushThreadBindings {v :bound})
         (try
           (alter-var-root v (constantly :new-root))
           (and (= :bound @v) (= :new-root (.getRawRoot v)))
           (finally
             (clojure.lang.Var/popThreadBindings)))))

(probe "alter/pop-reveals-new-root"
       (let [v (fresh-var :root true)]
         (clojure.lang.Var/pushThreadBindings {v :bound})
         (try
           (alter-var-root v (constantly :new-root))
           (finally
             (clojure.lang.Var/popThreadBindings)))
         (= :new-root @v)))

(probe "alter/concurrent-increments"
       (let [v (fresh-var 0 false)
             n 200
             threads 8
             latch (java.util.concurrent.CountDownLatch. threads)
             start (java.util.concurrent.CountDownLatch. 1)
             err (atom nil)]
         (dotimes [_ threads]
           (.start (Thread. (fn []
                              (.await start)
                              (try
                                (dotimes [_ n]
                                  (alter-var-root v inc))
                                (catch Throwable t
                                  (reset! err t))
                                (finally
                                  (.countDown latch)))))))
         (.countDown start)
         (.await latch)
         (if @err
           (throw-label @err)
           (= (* n threads) @v))))

(probe "alter/under-seq-first-next-nth-redef"
       (let [ok (atom true)
             restored (atom true)
             it (.iterator (core-seq-vars))]
         (while (.hasNext it)
           (let [cv (.next it)
                 target (fresh-var 0 false)
                 orig (.getRawRoot ^clojure.lang.Var cv)]
             (try
               (bind-root! cv mock-redefined)
               (when (not= 1 (alter-var-root target inc))
                 (reset! ok false))
               (finally
                 (bind-root! cv orig)
                 (when-not (identical? orig (.getRawRoot ^clojure.lang.Var cv))
                   (reset! restored false))))))
         (and @ok @restored)))

;; ---------------------------------------------------------------------------
;; thread bindings
;; ---------------------------------------------------------------------------

(probe "bind/push-pop-restores-root"
       (let [v (fresh-var :root true)]
         (clojure.lang.Var/pushThreadBindings {v :bound})
         (try
           (= :bound @v)
           (finally
             (clojure.lang.Var/popThreadBindings)))
         (= :root @v)))

(probe "bind/push-pop-restores-get-thread-bindings"
       (let [v (fresh-var :root true)
             before (get-thread-bindings)]
         (clojure.lang.Var/pushThreadBindings {v :bound})
         (try
           (= :bound (get (get-thread-bindings) v))
           (finally
             (clojure.lang.Var/popThreadBindings)))
         (= before (get-thread-bindings))))

(probe "bind/nested-lifo"
       (let [v (fresh-var :root true)
             saw (atom false)]
         (clojure.lang.Var/pushThreadBindings {v :a})
         (try
           (clojure.lang.Var/pushThreadBindings {v :b})
           (try
             (when (not= :b @v)
               (throw (ex-info "inner binding missing" {})))
             (finally
               (clojure.lang.Var/popThreadBindings)))
           (when (not= :a @v)
             (throw (ex-info "outer binding missing after inner pop" {})))
           (reset! saw true)
           (finally
             (clojure.lang.Var/popThreadBindings)))
         (and @saw (= :root @v))))

(probe "bind/parallel-vars"
       (let [a (fresh-var :ra true)
             b (fresh-var :rb true)]
         (clojure.lang.Var/pushThreadBindings {a :a b :b})
         (try
           (and (= :a @a) (= :b @b))
           (finally
             (clojure.lang.Var/popThreadBindings)))
         (and (= :ra @a) (= :rb @b))))

(probe "bind/binding-exception-unwinds"
       (let [v (fresh-var :root true)
             threw (try
                     (push-thread-bindings {v :bound})
                     (try
                       (throw (ex-info "boom" {}))
                       (finally
                         (clojure.lang.Var/popThreadBindings)))
                     false
                     (catch Exception _ true))]
         (and threw (= :root @v))))

(probe "bind/with-bindings-star-exception-unwinds"
       (let [v (fresh-var :root true)
             threw (try
                     (with-bindings* {v :bound}
                       (fn [] (throw (ex-info "boom" {}))))
                     false
                     (catch Exception _ true))]
         (and threw (= :root @v))))

(probe "bind/raw-child-thread-sees-root"
       (let [v (fresh-var :root true)
             p (java.util.concurrent.CompletableFuture.)]
         (clojure.lang.Var/pushThreadBindings {v :bound})
         (try
           (.start (Thread. (fn [] (.complete p @v))))
           (= :root (.get p 5 java.util.concurrent.TimeUnit/SECONDS))
           (finally
             (clojure.lang.Var/popThreadBindings)))))

(probe "bind/bound-fn-conveys"
       (let [v (fresh-var :root true)
             f (do
                 (clojure.lang.Var/pushThreadBindings {v :bound})
                 (try
                   (bound-fn* (fn [] @v))
                   (finally
                     (clojure.lang.Var/popThreadBindings))))]
         (= :bound (f))))

(probe "bind/future-conveys"
       (let [v (fresh-var :root true)
             fut (do
                   (clojure.lang.Var/pushThreadBindings {v :bound})
                   (try
                     (future @v)
                     (finally
                       (clojure.lang.Var/popThreadBindings))))]
         (= :bound (deref fut 5000 :timeout))))

(probe "bind/non-dynamic-throws-without-frame-change"
       (let [v (fresh-var :root false)
             before (get-thread-bindings)
             threw (try
                     (push-thread-bindings {v :x})
                     false
                     (catch IllegalStateException _ true))]
         (and threw (= :root @v) (= before (get-thread-bindings)))))

(probe "bind/unmatched-pop-on-fresh-thread"
       (let [p (java.util.concurrent.CompletableFuture.)]
         (.start
          (Thread.
           (fn []
             (let [threw (try
                           (clojure.lang.Var/popThreadBindings)
                           false
                           (catch IllegalStateException _ true))
                   v (fresh-var :root true)]
               (clojure.lang.Var/pushThreadBindings {v :bound})
               (try
                 (.complete p (and threw (= :bound @v)))
                 (finally
                   (clojure.lang.Var/popThreadBindings)))))))
         (boolean (.get p 5 java.util.concurrent.TimeUnit/SECONDS))))

(probe "bind/under-seq-first-next-nth-redef"
       (let [ok (atom true)
             restored (atom true)
             it (.iterator (core-seq-vars))]
         (while (.hasNext it)
           (let [cv (.next it)
                 v (fresh-var :root true)
                 orig (.getRawRoot ^clojure.lang.Var cv)]
             (try
               (bind-root! cv mock-redefined)
               (clojure.lang.Var/pushThreadBindings {v :bound})
               (try
                 (when (not= :bound @v)
                   (reset! ok false))
                 (finally
                   (clojure.lang.Var/popThreadBindings)))
               (when (not= :root @v)
                 (reset! ok false))
               (finally
                 (bind-root! cv orig)
                 (when-not (identical? orig (.getRawRoot ^clojure.lang.Var cv))
                   (reset! restored false))))))
         (and @ok @restored)))

;; ---------------------------------------------------------------------------
;; remove-method
;; ---------------------------------------------------------------------------

(defn- fresh-mm
  [hierarchy]
  (clojure.lang.MultiFn. (.concat "probe-mm-" (str (System/nanoTime)))
                         identity
                         :default
                         hierarchy))

(probe "mm/warmed-remove-falls-to-default"
       (let [mm (fresh-mm #'clojure.core/global-hierarchy)]
         (.addMethod mm :a (fn [_] :a))
         (.addMethod mm :default (fn [_] :default))
         (let [warmed (.invoke mm :a)]
           (remove-method mm :a)
           (and (= :a warmed) (= :default (.invoke mm :a))))))

(probe "mm/hierarchy-fallback-after-remove"
       (let [h (atom (-> (make-hierarchy) (derive :child :parent)))
             mm (fresh-mm h)]
         (.addMethod mm :parent (fn [_] :parent))
         (.addMethod mm :child (fn [_] :child))
         (let [warmed (.invoke mm :child)]
           (remove-method mm :child)
           (and (= :child warmed) (= :parent (.invoke mm :child))))))

(probe "mm/methods-drops-dispatch-value"
       (let [mm (fresh-mm #'clojure.core/global-hierarchy)]
         (.addMethod mm :a (fn [_] :a))
         (.addMethod mm :b (fn [_] :b))
         (remove-method mm :a)
         (and (not (contains? (methods mm) :a))
              (contains? (methods mm) :b))))

(probe "mm/absent-remove-is-idempotent"
       (let [mm (fresh-mm #'clojure.core/global-hierarchy)]
         (.addMethod mm :a (fn [_] :a))
         (let [before (methods mm)]
           (remove-method mm :missing)
           (and (= :a (.invoke mm :a))
                (= before (methods mm))))))

(probe "mm/remove-then-readd"
       (let [mm (fresh-mm #'clojure.core/global-hierarchy)]
         (.addMethod mm :a (fn [_] :a))
         (.addMethod mm :default (fn [_] :default))
         (.invoke mm :a)
         (remove-method mm :a)
         (let [after-remove (.invoke mm :a)]
           (.addMethod mm :a (fn [_] :a2))
           (and (= :default after-remove) (= :a2 (.invoke mm :a))))))

(probe "mm/under-seq-first-next-nth-redef"
       (let [ok (atom true)
             restored (atom true)
             it (.iterator (core-seq-vars))]
         (while (.hasNext it)
           (let [cv (.next it)
                 orig (.getRawRoot ^clojure.lang.Var cv)
                 mm (fresh-mm #'clojure.core/global-hierarchy)]
             (.addMethod mm :a (fn [_] :a))
             (.addMethod mm :default (fn [_] :default))
             (.invoke mm :a)
             (try
               (bind-root! cv mock-redefined)
               (remove-method mm :a)
               (when (not= :default (.invoke mm :a))
                 (reset! ok false))
               (finally
                 (bind-root! cv orig)
                 (when-not (identical? orig (.getRawRoot ^clojure.lang.Var cv))
                   (reset! restored false))))))
         (and @ok @restored)))

(shutdown-agents)
