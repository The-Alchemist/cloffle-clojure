;; Proxy/reify for Java interfaces; InvocationHandler-style callbacks without full reflection.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util Comparator)
        '(java.util.concurrent Callable))

(probe "java72/reify-Comparator"
       (let [^Comparator c (reify Comparator
                             (compare [_ a b] (compare (int a) (int b))))]
         (.compare c 2 1)))
(probe "java72/reify-Callable"
       (let [^Callable c (reify Callable (call [_] 99))]
         (.call c)))
(probe "java72/proxy-Runnable"
       (let [ran (atom false)
             ^Runnable r (proxy [Runnable] []
                           (run [_] (reset! ran true)))]
         (.run r) (boolean @ran)))
(probe "java72/Comparator-lambda-style"
       (.compare (Comparator/comparing (reify java.util.function.Function
                                          (apply [_ x] (count (str x)))))
                 "aa" "b"))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
