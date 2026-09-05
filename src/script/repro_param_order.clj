;; Standalone repro: Clojure maps do not guarantee seq order.
;;
;; Cloffle keyword map literals are PersistentShapeMap (Keyword.id order).
;; JVM Clojure small literals happen to be PersistentArrayMap (insertion order).
;; Unpatched Reitit walks :parameters with `for` / `map` + `into {}`, so that
;; order becomes the OpenAPI/Swagger :parameters vector.
;;
;; Cloffle fix: src/external-projects/patches/reitit/0004-deterministic-parameter-order.patch
;; selects locations in spec order (OpenAPI query/header/cookie/path;
;; Swagger query/body/formData/header/path) and does not change
;; PersistentShapeMap iteration.
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

(def openapi-in-order [:query :header :cookie :path])
(def swagger-in-order [:query :body :formData :header :path])

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
      patched (mapv name
                    (keep (fn [in]
                            (when (get parameters in) in))
                          openapi-in-order))]
  (println "  raw seq :in order:    " emitted)
  (println "  after spec-order keep:" patched)
  (println "  expected:             [\"query\" \"header\" \"cookie\" \"path\"]")
  (println "  raw match?" (= ["query" "header" "cookie" "path"] emitted))
  (println "  patched match?" (= ["query" "header" "cookie" "path"] patched)))

(println)
(println "=== 3. Unpatched Swagger remap + into {} ===")
(let [swagger-parameter {:query :query :body :body :form :formData :header :header :path :path :multipart :formData}
      parameters {:query 1 :body 2 :form 3 :header 4 :path 5}
      remapped (into {}
                     (keep (fn [[k v]]
                             (when-let [in (swagger-parameter k)]
                               [in v]))
                           parameters))
      patched (into (array-map)
                    (keep (fn [k]
                            (when-let [v (get remapped k)]
                              [k v]))
                          swagger-in-order))
      emitted (vec (keys remapped))
      patched-keys (vec (keys patched))]
  (dump "after into {}" remapped)
  (dump "after spec-order array-map" patched)
  (println "  expected:          [\"query\" \"body\" \"formData\" \"header\" \"path\"]")
  (println "  raw match?" (= [:query :body :formData :header :path] emitted))
  (println "  patched match?" (= [:query :body :formData :header :path] patched-keys)))

(println)
(println "=== 4. Unpatched GET :parameters {:query ... :path ...} ===")
(let [parameters {:query {:x 1 :y 1} :path {:z 1}}
      emitted (vec (for [[in schema] parameters
                         [k _] schema]
                     [(name in) k]))
      patched (vec (for [in openapi-in-order
                         :let [schema (get parameters in)]
                         :when schema
                         [k _] schema]
                     [(name in) k]))]
  (println "  raw:     " emitted)
  (println "  patched: " patched)
  (println "  expected: [[\"query\" :x] [\"query\" :y] [\"path\" :z]]")
  (println "  raw match?" (= [["query" :x] ["query" :y] ["path" :z]] emitted))
  (println "  patched match?" (= [["query" :x] ["query" :y] ["path" :z]] patched)))
