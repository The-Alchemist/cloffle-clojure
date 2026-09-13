;; java.lang.Math static methods (host math interop).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "java56/abs-int" (Math/abs -7))
(probe "java56/abs-long" (Math/abs -7))
(probe "java56/max" (Math/max 3 5))
(probe "java56/min" (Math/min 3 5))
(probe "java56/floor" (Math/floor 3.7))
(probe "java56/ceil" (Math/ceil 3.2))
(probe "java56/round" (Math/round 3.5))
(probe "java56/sqrt" (Math/sqrt 9.0))
(probe "java56/pow" (Math/pow 2.0 3.0))
(probe "java56/addExact" (Math/addExact 1 2))
(probe "java56/multiplyExact-throw"
       (try (Math/multiplyExact Long/MAX_VALUE 2)
            (catch Throwable t (.getName (class t)))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
