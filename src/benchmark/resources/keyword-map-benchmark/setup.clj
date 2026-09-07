(def small-m {:a 1 :b 2 :c 3})
(defn get-small [m] (get m :b))

(def large-m {:k0 0 :k1 1 :k2 2 :k3 3 :k4 4 :k5 5 :k6 6 :k7 7 :k8 8 :k9 9 :k10 10 :k11 11 :k12 12 :k13 13 :k14 14 :k15 15 :k16 16 :k17 17})
(defn get-large [m] (get m :k5))

(def shape-m12 {:k0 0 :k1 1 :k2 2 :k3 3 :k4 4 :k5 5 :k6 6 :k7 7 :k8 8 :k9 9 :k10 10 :k11 11})
(defn get-shape12 [m] (get m :k6))

(defn kw-invoke [m] (:b m))

(def nested-m {:user {:profile {:name "Alice"}}})
(defn get-in-nested [m] (get-in m [:user :profile :name]))

(defn guest-get-in-ephemeral-pipeline [x]
  (let [m {:user {:profile {:id x :role :admin}}}]
    (get-in m [:user :profile :id])))

(defn assoc-pipeline [m] (get (assoc m :status :active) :status))

(defn shape8-promote [m v] (:transition-ninth (assoc m :transition-ninth v)))

(defn assoc-pipe12 [m] (get (assoc m :status :active) :status))

(defn guest-ephemeral-pipeline [x]
  (let [m {:a x :b 2 :c 3}]
    (:a (assoc m :a "replacement"))))

(defn guest-ephemeral-insert [x]
  (let [m {:a 1 :b 2}
        m2 (assoc m :c x)]
    (if (= (:a m2) 1)
      (:c m2)
      nil)))

(defn guest-ephemeral-promote8 [x]
  (let [m {:p0 0 :p1 1 :p2 2 :p3 3 :p4 4 :p5 5 :p6 6 :p7 7}
        m2 (assoc m :p8 x)]
    (if (= (:p0 m2) 0)
      (:p8 m2)
      nil)))

(defn guest-tuple-destructure [x y]
  (let [[a b] [x y]]
    (if (= a x)
      b
      nil)))

(defn guest-list-ephemeral-pipeline [x y]
  (let [[a b] (list x y)]
    (if (= a x)
      b
      nil)))

(defn guest-lazy-seq-first [x]
  (first (lazy-seq [x])))

(defn guest-cons-first [x]
  (first (cons x nil)))

(defn guest-lazy-seq-cons-first [x]
  (first (lazy-seq (cons x nil))))

(defn guest-lazy-seq-apply-first [x]
  (let [f inc]
    (first (lazy-seq [(f x)]))))

(defn guest-lazy-seq-when-seq-first [x]
  (first (lazy-seq
          (when-let [s (seq [x])]
            [(first s)]))))

(defn guest-map-first [x]
  (first (map inc [x])))

(defn guest-map-second [x y]
  (second (map inc [x y])))

(defn guest-mapped-vector-reduce [x y]
  (reduce + 0 (clojure.lang.MappedVectorSeq/create inc [x y] 0)))

(defn guest-mapped-map-first [k v]
  (val (first (clojure.lang.MappedMapSeq/create identity {k v}))))

(defn guest-stream-seq-pipeline [x y]
  (into [] (comp (map identity) (filter keyword?)) [x y]))

(def pipeline-keys #{:pea-a :pea-b})

(defn guest-pipeline-into [k1 k2]
  (into [] (map name (filter pipeline-keys [k1 k2]))))

(defn guest-pipeline-vec [k1 k2]
  (vec (filter pipeline-keys [k1 k2])))

(defn guest-pipeline-reduce [k1 k2]
  (reduce (fn [acc k] k) :none (filter pipeline-keys [k1 k2])))

(defn guest-pipeline-take-drop [k1 k2 k3]
  (into [] (take 2 (drop 1 [k1 k2 k3]))))

(defn guest-pipeline-xform-control [k1 k2]
  (into [] (comp (filter pipeline-keys) (map name)) [k1 k2]))

(defn guest-ring-pipeline [body]
  (let [resp {:status 200 :headers {:content-type "text/plain"} :body body}
        resp2 (assoc resp :headers (assoc (:headers resp) :server "cloffle"))
        resp3 (assoc resp2 :status 201)
        {:keys [status headers body]} resp3]
    (if (and (= status 201)
             (= (:server headers) "cloffle")
             (= (:content-type headers) "text/plain"))
      body
      nil)))

(defn guest-hiccup-normalize [tag-name content-str]
  (let [elem [tag-name {:class "btn" :href "/home"} content-str]
        t (nth elem 0)
        second-el (nth elem 1)
        attrs (if (instance? clojure.lang.IPersistentMap second-el) second-el nil)
        content (if (instance? clojure.lang.IPersistentMap second-el) (nth elem 2) second-el)
        norm [t attrs content]
        final-tag (nth norm 0)
        final-attrs (nth norm 1)
        final-content (nth norm 2)]
    (if (and (= final-tag tag-name)
             (= (:href final-attrs) "/home"))
      final-content
      nil)))

(defn guest-tuple2-transform [x y]
  (let [[a b] [x y]
        [c d] [b a]]
    c))

(defn guest-kwargs-destructure [timeout]
  (let [opts {:method :post :timeout timeout}
        {:keys [method timeout] :or {method :get timeout 1000}} opts]
    (if (= method :post) timeout 0)))

(defn guest-middleware-pipeline [raw-body]
  (let [req {:uri "/api/data" :request-method :post :headers {:content-type "application/json"} :body raw-body}
        req2 (assoc req :params {:query "search"})
        req3 (assoc req2 :session {:user "alice"})
        {:keys [uri request-method headers params session body]} req3]
    (if (and (= request-method :post)
             (= (:user session) "alice")
             (= (:query params) "search")
             (= (:content-type headers) "application/json"))
      body
      nil)))

(defn guest-cond-option-pipeline [raw-timeout]
  (let [opts (-> {}
                 (cond-> true (assoc :id "btn"))
                 (cond-> true (assoc :role "primary"))
                 (cond-> true (assoc :href "/submit"))
                 (cond-> raw-timeout (assoc :timeout raw-timeout)))
        {:keys [id role href timeout]} opts]
    (if (and (= id "btn")
             (= role "primary")
             (= href "/submit"))
      timeout
      nil)))

(defn guest-event-enrich-pipeline [payload-str]
  (let [event {:id 101 :type :auth :user "alice" :tenant "org-1"
               :ip "127.0.0.1" :status :ok :timestamp 1700000000 :version 1}
        enriched (assoc event :payload payload-str)
        {:keys [id status user payload]} enriched]
    (if (and (= id 101)
             (= status :ok)
             (= user "alice"))
      payload
      nil)))

(defn guest-ephemeral-dissoc [x]
  (let [m {:a 1 :b x :c 3}
        m2 (dissoc m :b)]
    (if (= (:a m2) 1)
      (:c m2)
      nil)))

(defn guest-event-sanitize-pipeline [token]
  (let [event {:id 101 :user "alice" :secret token :temp 999 :status :ok}
        sanitized (-> event (dissoc :secret) (dissoc :temp))
        {:keys [id user secret temp status]} sanitized]
    (if (and (= id 101)
             (= status :ok)
             (= user "alice")
             (nil? secret)
             (nil? temp))
      id
      nil)))

(defn guest-cheshire-field-name [payload]
  (let [k1 :status
        k2 :user/id
        k3 "raw_field"
        f1 (if (keyword? k1) (.substring (str k1) 1) (str k1))
        f2 (if (keyword? k2) (.substring (str k2) 1) (str k2))
        f3 (if (keyword? k3) (.substring (str k3) 1) (str k3))]
    (if (and (= f1 "status")
             (= f2 "user/id")
             (= f3 "raw_field"))
      payload
      nil)))
