;; java.util.concurrent: ExecutorService, Future, Callable, TimeUnit (bounded).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util.concurrent Executors TimeUnit Callable Future CountDownLatch))

(probe "java71/Callable-submit"
       (let [^java.util.concurrent.ExecutorService es (Executors/newSingleThreadExecutor)]
         (try (.get (.submit es ^Callable (fn [] (+ 40 2))) 5 TimeUnit/SECONDS)
              (finally (.shutdown es)))))
(probe "java71/invokeAll"
       (let [^java.util.concurrent.ExecutorService es (Executors/newFixedThreadPool 2)]
         (try (count (.invokeAll es [(fn [] 1) (fn [] 2)]))
              (finally (.shutdown es)))))
(probe "java71/CountDownLatch"
       (let [latch (CountDownLatch. 1)]
         (.countDown latch) (.getCount latch)))
(probe "java71/Future-done"
       (let [^java.util.concurrent.ExecutorService es (Executors/newSingleThreadExecutor)]
         (try (let [^Future f (.submit es ^Callable (fn [] 1))]
                (.get f) (.isDone f))
              (finally (.shutdown es)))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
