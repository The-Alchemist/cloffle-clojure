;; ThreadLocal and InheritableThreadLocal (same-thread probes).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(def ^:private tl (ThreadLocal.))
(def ^:private itl (InheritableThreadLocal.))

(probe "java91/ThreadLocal" (do (.set tl 99) (.get tl)))
(probe "java91/ThreadLocal-remove" (do (.set tl 1) (.remove tl) (.get tl)))
(probe "java91/InheritableThreadLocal" (do (.set itl "v") (.get itl)))
(probe "java91/Thread-current" (boolean (some? (.getName (Thread/currentThread)))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
