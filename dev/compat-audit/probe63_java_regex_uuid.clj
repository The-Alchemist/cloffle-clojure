;; java.util.regex Pattern/Matcher and java.util.UUID interop.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util UUID)
        '(java.util.regex Pattern Matcher))

(def ^:private pat (Pattern/compile "(\\d+)-(\\d+)"))
(probe "java63/UUID-fromString" (str (UUID/fromString "550e8400-e29b-41d4-a716-446655440000")))
(probe "java63/UUID-nameUUIDFromBytes" (str (UUID/nameUUIDFromBytes (.getBytes "seed" "UTF-8"))))
(probe "java63/Pattern-matches" (Pattern/matches "[a-z]+" "abc"))
(probe "java63/Matcher-find"
       (let [^Matcher m (.matcher pat "12-34")] (.find m) (.group m 1)))
(probe "java63/Matcher-groups"
       (let [^Matcher m (.matcher pat "9-1")] (.matches m) [(.group m 1) (.group m 2)]))
(probe "java63/Pattern-split" (vec (.split (Pattern/compile ",") "a,b,c")))
(probe "java63/Pattern-escaped" (Pattern/matches "a\\.b" "a.b"))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
