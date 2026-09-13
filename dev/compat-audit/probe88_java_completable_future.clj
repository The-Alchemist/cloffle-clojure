;; CompletableFuture with explicit executor (bounded, shutdown).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util.concurrent CompletableFuture Executors TimeUnit))

(defn- with-single-executor [f]
  (let [^java.util.concurrent.ExecutorService es (Executors/newSingleThreadExecutor)]
    (try (f es) (finally (.shutdown es)))))

(probe "java88/completedFuture" (.get (CompletableFuture/completedFuture 42)))
(probe "java88/supplyAsync"
       (with-single-executor
        (fn [es] (.get (.supplyAsync (fn [] (+ 10 32)) es) 5 TimeUnit/SECONDS))))
(probe "java88/thenApply"
       (with-single-executor
        (fn [es]
          (.get (.thenApply (.supplyAsync (fn [] 1) es) (reify java.util.function.Function
                                                         (apply [_ x] (inc (int x)))))
                5 TimeUnit/SECONDS))))
(probe "java88/allOf"
       (with-single-executor
        (fn [es]
          (.get (.thenApply (CompletableFuture/allOf (into-array CompletableFuture
                                                                 [(CompletableFuture/completedFuture 1)
                                                                  (CompletableFuture/completedFuture 2)]))
                            (reify java.util.function.Function
                              (apply [_ _] :done)))
                5 TimeUnit/SECONDS))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
