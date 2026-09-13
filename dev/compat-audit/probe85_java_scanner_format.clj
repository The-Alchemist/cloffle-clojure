;; Scanner, Formatter, PrintWriter on in-memory strings.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util Scanner Formatter Locale)
        '(java.io PrintWriter StringWriter))

(probe "java85/Scanner-next" (with-open [^Scanner sc (Scanner. "42 99")] (.nextInt sc)))
(probe "java85/Scanner-line" (with-open [^Scanner sc (Scanner. "a\nb")] (.nextLine sc)))
(probe "java85/Formatter"
       (let [^Formatter f (Formatter. Locale/US)] (.format f "%d-%s" 1 "x") (.toString f)))
(probe "java85/PrintWriter" (let [^StringWriter sw (StringWriter.)] (doto (PrintWriter. sw) (.print "hi") (.flush)) (.toString sw)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
