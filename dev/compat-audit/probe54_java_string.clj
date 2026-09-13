;; Java String / Character interop: instance methods, static helpers, chaining.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.lang Character String))

(probe "java54/length" (.length "abc"))
(probe "java54/isEmpty" (.isEmpty ""))
(probe "java54/substring" (.substring "hello" 1 4))
(probe "java54/startsWith" (.startsWith "prefix-x" "prefix"))
(probe "java54/endsWith" (.endsWith "x-suffix" "suffix"))
(probe "java54/indexOf" (.indexOf "aba" "a"))
(probe "java54/toUpperCase" (.toUpperCase "ab"))
(probe "java54/equals" (.equals "a" "a"))
(probe "java54/Character-digit" (Character/digit \7 10))
(probe "java54/Character-isWhitespace" (Character/isWhitespace \space))
(probe "java54/concat-instance" (.concat "a" "b"))
(probe "java54/valueOf-int" (String/valueOf 42))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
