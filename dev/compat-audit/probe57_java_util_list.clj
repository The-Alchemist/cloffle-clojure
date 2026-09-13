;; java.util List / ArrayList / Collections interop.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util ArrayList Collections List))

(probe "java57/ArrayList-add"
       (let [^List al (ArrayList.)] (.add al 1) (.add al 2) (vec al)))
(probe "java57/ArrayList-size"
       (let [^List al (doto (ArrayList.) (.add "a") (.add "b"))] (.size al)))
(probe "java57/Collections-emptyList" (count (Collections/emptyList)))
(probe "java57/Collections-singleton" (vec (Collections/singleton "x")))
(probe "java57/Collections-sort"
       (let [^List al (ArrayList. [3 1 2])] (Collections/sort al) (vec al)))
(probe "java57/List-get"
       (let [^List al (ArrayList. [10 20])] (.get al 1)))
(probe "java57/contains" (let [^List al (ArrayList. [1 2])] (.contains al 2)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
