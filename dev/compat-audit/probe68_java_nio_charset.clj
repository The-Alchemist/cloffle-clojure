;; java.nio charset, ByteBuffer, CharBuffer interop.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.nio ByteBuffer CharBuffer ByteOrder)
        '(java.nio.charset Charset StandardCharsets))

(probe "java68/Charset-decode" (str (.decode (Charset/forName "UTF-8") (.getBytes "café" "UTF-8"))))
(probe "java68/StandardCharsets" (str (.decode StandardCharsets/UTF_8 (.getBytes "hi"))))
(probe "java68/ByteBuffer-putInt"
       (let [bb (ByteBuffer/allocate 4)] (.putInt bb 42) (.flip bb) (.getInt bb)))
(probe "java68/ByteBuffer-order"
       (let [bb (.order (ByteBuffer/allocate 2) ByteOrder/LITTLE_ENDIAN)]
         (.putShort bb (short 1)) (.flip bb) (.getShort bb)))
(probe "java68/CharBuffer-wrap" (str (.get (CharBuffer/wrap (.toCharArray "hi")))))
(probe "java68/encode-length" (count (.array (.encode StandardCharsets/UTF_8 "abc"))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
