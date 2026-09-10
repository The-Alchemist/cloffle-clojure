(def small-m {:a :va :b :vb :c :vc})
(defn get-small [m] (get m :b))
(defn bench-get-small [] (get small-m :b))
(defn bench-rt-get-small [] (clojure.lang.RT/get small-m :b))
(defn bench-interop-echo2 [] (net.javacrumbs.cloffle.bytecode.BytecodeStaticMethod/unwrap "foo"))

(def large-m {:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8 :k9 :v9 :k10 :v10 :k11 :v11 :k12 :v12 :k13 :v13 :k14 :v14 :k15 :v15 :k16 :v16 :k17 :v17})
(defn get-large [m] (get m :k5))
(defn bench-get-large [] (get large-m :k5))

(def shape-m12 {:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8 :k9 :v9 :k10 :v10 :k11 :v11})
(defn get-shape12 [m] (get m :k6))
(defn bench-get-shape12 [] (get shape-m12 :k6))

(defn kw-invoke [m] (:b m))
(defn bench-kw-invoke [] (:b small-m))

(def nested-m {:user {:profile {:name "Alice"}}})
(defn get-in-nested [m] (get-in m [:user :profile :name]))
(defn bench-get-in-nested [] (get-in nested-m [:user :profile :name]))

(defn guest-get-in-ephemeral-pipeline [x]
  (let [m {:user {:profile {:id x :role :admin}}}]
    (get-in m [:user :profile :id])))

(defn assoc-pipeline [m] (get (assoc m :status :active) :status))

(defn shape8-promote [m v] (:transition-ninth (assoc m :transition-ninth v)))

(defn assoc-pipe12 [m] (get (assoc m :status :active) :status))

(defn guest-shape16-insert-shared [m]
  (get (assoc m :status :active) :status))

(defn guest-ephemeral-pipeline [x]
  (let [m {:a x :b :vb :c :vc}]
    (:a (assoc m :a "replacement"))))

(defn guest-ephemeral-insert [x]
  (let [m {:a :va :b :vb}
        m2 (assoc m :c x)]
    (if (= (:a m2) :va)
      (:c m2)
      nil)))

(defn guest-ephemeral-promote8 [x]
  (let [m {:p0 :v0 :p1 :v1 :p2 :v2 :p3 :v3 :p4 :v4 :p5 :v5 :p6 :v6 :p7 :v7}
        m2 (assoc m :p8 x)]
    (if (= (:p0 m2) :v0)
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
  (let [f identity]
    (first (lazy-seq [(f x)]))))

(defn guest-lazy-seq-when-seq-first [x]
  (first (lazy-seq
          (when-let [s (seq [x])]
            [(first s)]))))

(defn guest-map-first [x]
  (first (map identity [x])))

(defn guest-map-second [x y]
  (second (map identity [x y])))

(defn guest-mapped-vector-reduce [x y]
  (reduce (fn [_ v] v) :none (clojure.lang.MappedVectorSeq/create identity [x y])))

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

;; PROVISIONAL: see guest-hiccup-normalize — the take/drop counts are numeric operands.
(defn guest-pipeline-take-drop [k1 k2 k3]
  (into [] (take 2 (drop 1 [k1 k2 k3]))))

(defn guest-pipeline-xform-control [k1 k2]
  (into [] (comp (filter pipeline-keys) (map name)) [k1 k2]))

(defn guest-const-nested-headers [body]
  (let [m {:status 200
           :headers {:content-type "text/plain" :server "cloffle"}
           :body body}]
    [(:status m) (:content-type (:headers m)) (:body m)]))

(defn guest-all-const-nested []
  (let [m {:status 200 :headers {:content-type "text/plain"} :body "ok"}]
    [(:status m) (:content-type (:headers m)) (:body m)]))

(defn guest-const-int-key-map []
  (let [m {1 :a}] (get m 1)))

;; Three levels of constant keyword maps; dynamic leaf only.
(defn guest-const-nested-deep [token]
  (let [m {:trace {:span {:id token :kind :server :peer "upstream"}
                    :flags {:sampled true :debug false}}
           :meta {:version 1 :schema :v2}
           :ok true}]
    (get-in m [:trace :span :id])))

;; Ring-shaped: constant nested headers + chained header assoc + destructure (heavier than guest-const-nested-headers).
(defn guest-const-nested-ring-plus [body]
  (let [resp {:status 200
              :headers {:content-type "text/plain" :server "cloffle" :cache-control "no-store"}
              :body body}
        resp2 (assoc resp :headers (assoc (:headers resp) :x-request-id "rid-1"))
        resp3 (assoc resp2 :status 201)
        {:keys [status headers body]} resp3]
    (if (and (= status 201)
             (= (:x-request-id headers) "rid-1")
             (= (:cache-control headers) "no-store")
             (= (:content-type headers) "text/plain"))
      body
      nil)))

;; JSON:API-ish envelope: several constant nested maps, one dynamic attribute field.
(defn guest-const-nested-api-envelope [summary]
  (let [doc {:data {:type "articles"
                    :id "article-101"
                    :attributes {:title "Shape maps"
                                 :summary summary
                                 :tags {:runtime true :maps true}}}
             :meta {:request-id "req-1" :version "v1"}
             :links {:self "/articles/article-101"}}
        updated (assoc-in doc [:data :attributes :summary] summary)]
    (get-in updated [:data :attributes :summary])))

;; Outer map with four independent constant nested keyword maps plus one dynamic slot.
(defn guest-const-nested-multi-slot [user-id]
  (let [m {:headers {:content-type "application/json" :accept "*/*"}
           :session {:user-id user-id :role :reader}
           :params {:locale "en-US" :format :json}
           :cookies {:sid "abc"}
           :meta {:trace "t-1"}}]
    [(:content-type (:headers m))
     (:user-id (:session m))
     (:locale (:params m))
     (:sid (:cookies m))
     (:trace (:meta m))]))

(defn guest-const-inner4-fanout-best [tag]
  (let [h0 {:content-type "text/plain" :server "cloffle" :cache-control "no-store" :accept "*/*"}
        h1 {:content-type "text/plain" :server "cloffle" :cache-control "no-store" :accept "*/*"}
        h2 {:content-type "text/plain" :server "cloffle" :cache-control "no-store" :accept "*/*"}
        h3 {:content-type "text/plain" :server "cloffle" :cache-control "no-store" :accept "*/*"}
        h4 {:content-type "text/plain" :server "cloffle" :cache-control "no-store" :accept "*/*"}
        h5 {:content-type "text/plain" :server "cloffle" :cache-control "no-store" :accept "*/*"}
        h6 {:content-type "text/plain" :server "cloffle" :cache-control "no-store" :accept "*/*"}
        h7 {:content-type "text/plain" :server "cloffle" :cache-control "no-store" :accept "*/*"}]
    (+ (if (= (:cache-control h0) "no-store") 1 0)
       (if (= (:accept h1) "*/*") 1 0)
       (if (= (:cache-control h2) "no-store") 1 0)
       (if (= (:accept h3) "*/*") 1 0)
       (if (= (:cache-control h4) "no-store") 1 0)
       (if (= (:accept h5) "*/*") 1 0)
       (if (= (:cache-control h6) "no-store") 1 0)
       (if (= (:accept h7) "*/*") 1 0)
       (count tag))))

(defn guest-const-inner-fanout-best [tag]
  (let [h0 {:content-type "text/plain" :server "cloffle"}
        h1 {:content-type "text/plain" :server "cloffle"}
        h2 {:content-type "text/plain" :server "cloffle"}
        h3 {:content-type "text/plain" :server "cloffle"}
        h4 {:content-type "text/plain" :server "cloffle"}
        h5 {:content-type "text/plain" :server "cloffle"}
        h6 {:content-type "text/plain" :server "cloffle"}
        h7 {:content-type "text/plain" :server "cloffle"}]
    (+ (if (= (:content-type h0) "text/plain") 1 0)
       (if (= (:server h1) "cloffle") 1 0)
       (if (= (:content-type h2) "text/plain") 1 0)
       (if (= (:server h3) "cloffle") 1 0)
       (if (= (:content-type h4) "text/plain") 1 0)
       (if (= (:server h5) "cloffle") 1 0)
       (if (= (:content-type h6) "text/plain") 1 0)
       (if (= (:server h7) "cloffle") 1 0)
       (count tag))))

(defn guest-const-nested-fanout-best [body]
  (let [m0 {:i 0 :headers {:content-type "text/plain" :server "cloffle"} :body body}
        m1 {:i 1 :headers {:content-type "text/plain" :server "cloffle"} :body body}
        m2 {:i 2 :headers {:content-type "text/plain" :server "cloffle"} :body body}
        m3 {:i 3 :headers {:content-type "text/plain" :server "cloffle"} :body body}
        m4 {:i 4 :headers {:content-type "text/plain" :server "cloffle"} :body body}
        m5 {:i 5 :headers {:content-type "text/plain" :server "cloffle"} :body body}
        m6 {:i 6 :headers {:content-type "text/plain" :server "cloffle"} :body body}
        m7 {:i 7 :headers {:content-type "text/plain" :server "cloffle"} :body body}]
    (+ (:i m0) (:i m1) (:i m2) (:i m3) (:i m4) (:i m5) (:i m6) (:i m7)
       (if (and (= (:content-type (:headers m0)) "text/plain")
                (= (:server (:headers m7)) "cloffle"))
         (count (str body (:body m4)))
         0))))

(defn guest-ring-pipeline [body]
  (let [resp {:status :ok :headers {:content-type "text/plain"} :body body}
        resp2 (assoc resp :headers (assoc (:headers resp) :server "cloffle"))
        resp3 (assoc resp2 :status :created)
        {:keys [status headers body]} resp3]
    (if (and (= status :created)
             (= (:server headers) "cloffle")
             (= (:content-type headers) "text/plain"))
      body
      nil)))

;; PROVISIONAL (2026-09-09): indexes with `nth`, so a boxed Long index is on the measured path and
;; its cost swamps the map work this file otherwise isolates. Kept because it models real hiccup
;; element normalization, but do not read it as a lowering-layer benchmark until primitives are
;; specialized. Same caveat applies to guest-pipeline-take-drop's numeric take/drop counts.
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
        {:keys [method timeout] :or {method :get timeout "1000ms"}} opts]
    (if (= method :post) timeout nil)))

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

(defn guest-ring-request-nested [payload]
  (let [req {:uri "/api/patients"
             :request-method :post
             :scheme :https
             :server-name "api.example.test"
             :server-port "8080"
             :remote-addr "203.0.113.10"
             :headers {:content-type "application/json"
                       :accept "application/json"
                       :authorization "Bearer token"
                       :user-agent "cloffle-client"
                       :x-request-id "req-101"}
             :body payload
             :params {:patient-id "patient-101" :format :json}
             :session {:user-id "user-7" :tenant-id "org-3" :role :clinician}
             :cookies {:session "cookie-token"}
             :query-string "include=coverage"
             :protocol "HTTP/1.1"
             :context "/api"}
        headers (assoc (:headers req) :x-trace-id "trace-202")
        enriched (assoc req :headers headers)]
    (if (= (:x-trace-id (:headers enriched)) "trace-202")
      (:body enriched)
      nil)))

(defn guest-fhir-patient-nested [patient-id]
  (let [patient {:resourceType "Patient"
                 :id "patient-101"
                 :meta {:versionId "v3" :lastUpdated "2026-09-09" :source "hospital-a" :profile "core-patient"}
                 :implicitRules "rules-v1"
                 :language "en"
                 :text {:status :generated :div "<div>Patient</div>"}
                 :identifier {:system "urn:mrn" :value patient-id :use :usual :assigner "hospital-a"}
                 :active :pending
                 :name {:use :official :family "Nguyen" :given "Avery" :prefix "Dr"}
                 :telecom {:system :phone :value "555-0100" :use :mobile :rank "primary"}
                 :gender :unknown
                 :birthDate "1985-04-12"
                 :deceased :unknown
                 :address {:use :home :line "10 Main St" :city "Boston" :state "MA" :postalCode "02110" :country "US"}
                 :maritalStatus {:coding "unknown" :text "Unknown"}
                 :managingOrganization {:reference "Organization/org-3" :display "Example Health"}}
        active-patient (assoc patient :active :active)]
    (if (= (:active active-patient) :active)
      (:value (:identifier active-patient))
      nil)))

(defn guest-jsonapi-document-nested [summary]
  (let [document {:data {:type "articles"
                         :id "article-101"
                         :attributes {:title "Shape maps in practice"
                                      :slug "shape-maps"
                                      :status :published
                                      :author "Avery"
                                      :locale "en-US"
                                      :category "runtime"
                                      :summary "old-summary"
                                      :body "Article body"
                                      :published-at "2026-09-09"
                                      :revision "v4"}
                         :relationships {:author {:type "people" :id "person-7"}
                                         :organization {:type "organizations" :id "org-3"}}
                         :links {:self "/articles/article-101"}}
                  :included {:type "people" :id "person-7" :attributes {:name "Avery" :role :author}}
                  :meta {:request-id "req-101" :version "v1"}
                  :links {:self "/articles/article-101" :next "/articles/article-102"}}
        data (:data document)
        attributes (assoc (:attributes data) :summary summary)
        updated (assoc document :data (assoc data :attributes attributes))]
    (:summary (:attributes (:data updated)))))

(defn guest-app-entity-16 [display-name]
  (let [entity {:id "user-101"
                :type :user
                :tenant-id "org-3"
                :email "avery@example.test"
                :username "avery"
                :status :pending
                :role :admin
                :created-at "2026-01-10"
                :updated-at "2026-09-09"
                :version "v7"
                :locale "en-US"
                :timezone "America/New_York"
                :profile {:display-name display-name :given-name "Avery" :family-name "Nguyen" :avatar "/avatars/101"}
                :settings {:theme :dark :digest :daily :notifications :enabled :date-format "yyyy-MM-dd"}
                :organization {:id "org-3" :name "Example Health" :plan :enterprise}
                :audit {:created-by "system" :updated-by "user-7" :source :api}}
        active-entity (assoc entity :status :active)]
    (if (= (:status active-entity) :active)
      (:display-name (:profile active-entity))
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
  (let [event {:id "evt-101" :type :auth :user "alice" :tenant "org-1"
               :ip "127.0.0.1" :status :ok :timestamp "2026-09-06" :version :v1}
        enriched (assoc event :payload payload-str)
        {:keys [id status user payload]} enriched]
    (if (and (= id "evt-101")
             (= status :ok)
             (= user "alice"))
      payload
      nil)))

(defn guest-ephemeral-dissoc [x]
  (let [m {:a :va :b x :c :vc}
        m2 (dissoc m :b)]
    (if (= (:a m2) :va)
      (:c m2)
      nil)))

(defn guest-event-sanitize-pipeline [token]
  (let [event {:id "evt-101" :user "alice" :secret token :temp "scratch" :status :ok}
        sanitized (-> event (dissoc :secret) (dissoc :temp))
        {:keys [id user secret temp status]} sanitized]
    (if (and (= id "evt-101")
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
