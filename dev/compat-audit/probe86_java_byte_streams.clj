;; ByteArrayOutputStream, DataOutputStream / DataInputStream roundtrip.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.io ByteArrayOutputStream ByteArrayInputStream DataOutputStream DataInputStream))

(defn- bytes-roundtrip []
  (let [^ByteArrayOutputStream baos (ByteArrayOutputStream.)
        _ (doto (DataOutputStream. baos) (.writeInt 42) (.writeUTF "ok"))
        ^bytes raw (.toByteArray baos)
        ^DataInputStream in (DataInputStream. (ByteArrayInputStream. raw))]
    [(.readInt in) (.readUTF in)]))

(probe "java86/data-roundtrip" (vec (bytes-roundtrip)))
(probe "java86/BAOS-size" (let [^ByteArrayOutputStream b (ByteArrayOutputStream.)] (.write b 1) (.write b 2) (.size b)))
(probe "java86/BAIS-read" (let [^ByteArrayInputStream in (ByteArrayInputStream. (byte-array [3 4]))] (.read in)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
