;; java.time: Instant, Duration, LocalDate, ZonedDateTime parsing and math.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.time Instant Duration LocalDate ZoneOffset ZonedDateTime))

(def ^:private inst (Instant/parse "2020-06-15T12:00:00Z"))
(probe "java67/Instant-epoch" (.getEpochSecond inst))
(probe "java67/Instant-plus" (str (.plus inst (Duration/ofSeconds 60))))
(probe "java67/Duration-between" (.toMinutes (Duration/between inst (.plus inst (Duration/ofHours 2)))))
(probe "java67/LocalDate-parse" (str (LocalDate/parse "2024-01-31")))
(probe "java67/LocalDate-plusDays" (str (.plusDays (LocalDate/of 2024 1 1) 10)))
(probe "java67/ZonedDateTime" (str (ZonedDateTime/of 2024 1 1 12 0 0 0 (ZoneOffset/UTC))))
(probe "java67/isBefore" (.isBefore (LocalDate/of 2020 1 1) (LocalDate/of 2021 1 1)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
