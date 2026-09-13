;; String / regex pipelines: map/filter on chars, clojure.string, re-find/re-seq chains.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(require '[clojure.string :as str])

(probe "edge24/str-map-filter" (vec (filter #(> (count %) 0) (map str/trim [" a " "" "b"]))))
(probe "edge24/split-map" (vec (map count (str/split "a,b,c" #","))))
(probe "edge24/join-filter" (str/join "," (filter seq ["a" "" "b" nil "c"])))
(probe "edge24/re-seq-pipe" (vec (map first (filter vector? (map vec (re-seq #"[a-z]+" "a1b22c"))))))
(probe "edge24/re-find-map" (vec (keep #(get % 0) (map #(re-find #"\d+" %) ["x1" "y" "z23"]))))
(probe "edge24/upper-filter" (vec (filter #(str/includes? % "A") (map str/upper-case ["ab" "cd" "ea"]))))
(probe "edge24/chars-pipe" (apply str (map char (filter #(< % 120) (map int "Hello!")))))
(probe "edge24/blank-filter" (vec (remove str/blank? [" " "" "x" "\t"])))
(probe "edge24/replace-seq" (vec (map #(str/replace % #"a" "A") ["aba" "ca" ""])))
(probe "edge24/lines-pipe" (vec (filter seq (str/split-lines "a\n\nb\nc"))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
