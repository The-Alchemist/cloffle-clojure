;; Multi-dim arrays, static fields, synchronized, BitSet.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util BitSet))

(probe "java73/multi-dim-array" (aget (make-array Integer/TYPE 2 3) 1 2))
(probe "java73/array-set" (aset (int-array 3) 1 9))
(probe "java73/Boolean-TRUE" (boolean Boolean/TRUE))
(probe "java73/Integer-MAX" Integer/MAX_VALUE)
(probe "java73/locking" (locking (Object.) (+ 1 2)))
(probe "java73/BitSet" (let [^BitSet bs (BitSet.)] (.set bs 3) (.get bs 3)))
(probe "java73/BitSet-cardinality" (let [^BitSet bs (doto (BitSet.) (.set 0) (.set 2))] (.cardinality bs)))
(probe "java73/System-arraycopy"
       (let [src (int-array [1 2 3]) dest (int-array 3)]
         (System/arraycopy src 0 dest 0 3) (vec dest)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
