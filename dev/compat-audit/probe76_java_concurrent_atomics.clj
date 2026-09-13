;; AtomicInteger, Semaphore, ReentrantLock (no deadlock).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util.concurrent.atomic AtomicInteger AtomicReference)
        '(java.util.concurrent Semaphore)
        '(java.util.concurrent.locks ReentrantLock))

(probe "java76/AtomicInteger" (let [^AtomicInteger a (AtomicInteger. 0)] (.addAndGet a 5)))
(probe "java76/AtomicReference"
       (let [^AtomicReference r (AtomicReference. "a")] (.set r "b") (.get r)))
(probe "java76/Semaphore" (let [^Semaphore s (Semaphore. 2)] (.acquire s) (.availablePermits s)))
(probe "java76/ReentrantLock"
       (let [^ReentrantLock l (ReentrantLock.)] (.lock l) (let [r (+ 1 2)] (.unlock l) r)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
