;; Standalone repro: Clojure maps do not guarantee seq order.
;;
;; Cloffle keyword map literals are PersistentShapeMap (Keyword.id order).
;; JVM Clojure small literals happen to be PersistentArrayMap (insertion order).
;; Unpatched Reitit walks :parameters with `for` / `map` + `into {}`, so that
;; order becomes the OpenAPI/Swagger :parameters vector.
;;
;; Cloffle fix: src/external-projects/patches/reitit/0004-deterministic-parameter-order.patch
;; sorts locations (OpenAPI query/header/cookie/path; Swagger query/body/formData/header/path)
;; and does not change PersistentShapeMap iteration.
;;
;; Run under Cloffle:
;;   java ... -cp "$(clojure -A:cloffle-java -Spath)" net.javacrumbs.cloffle.CloffleMain src/script/repro_param_order.clj
;; Run under official Clojure 1.12.0:
;;   java -cp clojure-1.12.0.jar:spec.alpha.jar:core.specs.alpha.jar clojure.main src/script/repro_param_order.clj

(defn kw-id [k]
  (try (.id ^clojure.lang.Keyword k)
       (catch Exception _ "n/a")))

(defn dump [label m]
  (println label)
  (println "  class:" (class m))
  (println "  keys: " (vec (keys m)))
  (doseq [k (keys m)]
    (println "   " k "id=" (kw-id k))))

(def openapi-in-order {:query 0 :header 1 :cookie 2 :path 3})
(def swagger-in-order {:query 0 :body 1 :formData 2 :header 3 :path 4})

(println "=== 1. OpenAPI all-parameter-types-test map ===")
(dump "literal"
      {:query :q :body :b :header :h :cookie :c :path :p})
(dump "array-map"
      (array-map :query :q :body :b :header :h :cookie :c :path :p))

(println)
(println "=== 2. Unpatched OpenAPI emission (dissoc :body, for over entries) ===")
(let [parameters {:query {:q 1} :body {:b 1} :header {:h 1} :cookie {:c 1} :path {:p 1}}
      parameters (dissoc parameters :body)
      emitted (vec (for [[in _] parameters] (name in)))
      patched (->> parameters
                   (sort-by (fn [[in _]] (get openapi-in-order in 99)))
                   (mapv (comp name first)))]
  (println "  raw seq :in order:    " emitted)
  (println "  after location sort:  " patched)
  (println "  expected:             [\"query\" \"header\" \"cookie\" \"path\"]")
  (println "  raw match?" (= ["query" "header" "cookie" "path"] emitted))
  (println "  patched match?" (= ["query" "header" "cookie" "path"] patched)))

(println)
(println "=== 3. Unpatched Swagger remap + into {} ===")
(let [swagger-parameter {:query :query :body :body :form :formData :header :header :path :path :multipart :formData}
      parameters {:query 1 :body 2 :form 3 :header 4 :path 5}
      remapped (->> parameters
                    (map (fn [[k v]] [(swagger-parameter k) v]))
                    (filter first)
                    (into {}))
      patched (->> parameters
                   (map (fn [[k v]] [(swagger-parameter k) v]))
                   (filter first)
                   (sort-by (fn [[k _]] (get swagger-in-order k 99)))
                   (reduce (fn [m [k v]] (assoc m k v)) (array-map)))
      emitted (vec (keys remapped))
      patched-keys (vec (keys patched))]
  (dump "after into {}" remapped)
  (dump "after sorted array-map" patched)
  (println "  expected:          [\"query\" \"body\" \"formData\" \"header\" \"path\"]")
  (println "  raw match?" (= [:query :body :formData :header :path] emitted))
  (println "  patched match?" (= [:query :body :formData :header :path] patched-keys)))

(println)
(println "=== 4. Unpatched GET :parameters {:query ... :path ...} ===")
(let [parameters {:query {:x 1 :y 1} :path {:z 1}}
      emitted (vec (for [[in schema] parameters
                         [k _] schema]
                     [(name in) k]))
      patched (vec (for [[in schema] (sort-by (fn [[in _]] (get openapi-in-order in 99)) parameters)
                         [k _] schema]
                     [(name in) k]))]
  (println "  raw:     " emitted)
  (println "  patched: " patched)
  (println "  expected: [[\"query\" :x] [\"query\" :y] [\"path\" :z]]")
  (println "  raw match?" (= [["query" :x] ["query" :y] ["path" :z]] emitted))
  (println "  patched match?" (= [["query" :x] ["query" :y] ["path" :z]] patched)))
