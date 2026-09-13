;; ArrayDeque, PriorityQueue, Queue/Deque methods.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util ArrayDeque PriorityQueue Queue Deque))

(probe "java79/ArrayDeque" (let [^Deque d (ArrayDeque.)] (.addLast d 1) (.addFirst d 0) (vec d)))
(probe "java79/PriorityQueue" (let [^Queue q (PriorityQueue.)] (.add q 3) (.add q 1) (.add q 2) (.poll q)))
(probe "java79/offer-poll" (let [^Queue q (ArrayDeque.)] (.offer q "a") (.poll q)))
(probe "java79/peek-empty" (let [^Queue q (PriorityQueue.)] (.peek q)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
