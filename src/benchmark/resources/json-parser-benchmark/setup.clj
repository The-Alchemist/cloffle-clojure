(def jsonapi
  (net.javacrumbs.cloffle.benchmark.JsonParserBenchmark/fixture
   "json-parser-benchmark/data/jsonapi.json"))
(def entity16
  (net.javacrumbs.cloffle.benchmark.JsonParserBenchmark/fixture
   "json-parser-benchmark/data/entity16.json"))
(def rows
  (net.javacrumbs.cloffle.benchmark.JsonParserBenchmark/fixture
   "json-parser-benchmark/data/rows.json"))
(def escaped
  (net.javacrumbs.cloffle.benchmark.JsonParserBenchmark/fixture
   "json-parser-benchmark/data/escaped.json"))

(def jsonapi-bytes (.getBytes jsonapi "UTF-8"))
(def entity16-bytes (.getBytes entity16 "UTF-8"))
(def github-json
  (net.javacrumbs.cloffle.benchmark.JsonParserBenchmark/fixture
   "json-parser-benchmark/data/github-clojure-repo.json"))
(def twitter-json
  (net.javacrumbs.cloffle.benchmark.JsonParserBenchmark/fixture
   "json-parser-benchmark/data/twitter.json"))
(def github-bytes (.getBytes github-json "UTF-8"))
(def twitter-bytes (.getBytes twitter-json "UTF-8"))
(def twitter-truffle
  (com.oracle.truffle.api.strings.TruffleString/fromJavaStringUncached
   twitter-json
   com.oracle.truffle.api.strings.TruffleString$Encoding/UTF_8))
(def github-buffer (java.nio.ByteBuffer/wrap github-bytes))
(def twitter-buffer (java.nio.ByteBuffer/wrap twitter-bytes))

(require '[cloffle.json :as json])

(defn guest-parse-lookup-jsonapi []
  (get-in (json/parse-string jsonapi) [:data :attributes :title]))

(defn guest-parse-lookup-entity16 []
  (:email (json/parse-string entity16)))

(defn guest-parse-lookup-rows []
  (:name (nth (json/parse-string rows) 3)))

;; Control: the parse result is named and escapes, so the fused rewrite must decline and this must
;; stay at baseline cost.
(defn guest-parse-escape-jsonapi []
  (let [m (json/parse-string jsonapi)]
    m))

(defn guest-parse-escape-entity16 []
  (let [m (json/parse-string entity16)]
    m))

(defn guest-project-jsonapi []
  (let [m (json/parse-string jsonapi)]
    {:title (get-in m [:data :attributes :title])
     :id    (get-in m [:data :id])
     :rid   (get-in m [:meta :request-id])}))

(defn guest-project-entity16 []
  (let [m (json/parse-string entity16)]
    (select-keys m [:id :email :status])))

(defn guest-project-jsonapi-bytes []
  (let [m (json/parse-bytes jsonapi-bytes)]
    {:title (get-in m [:data :attributes :title])
     :id    (get-in m [:data :id])
     :rid   (get-in m [:meta :request-id])}))

(defn guest-project-entity16-bytes []
  (let [m (json/parse-bytes entity16-bytes)]
    (select-keys m [:id :email :status])))

(defn guest-project-github []
  (let [m (json/parse-string github-json)]
    {:full_name (:full_name m)
     :stargazers_count (:stargazers_count m)
     :open_issues_count (:open_issues_count m)
     :owner {:login (get-in m [:owner :login])}}))

(defn guest-project-github-bytes []
  (let [m (json/parse-bytes github-bytes)]
    {:full_name (:full_name m)
     :stargazers_count (:stargazers_count m)
     :open_issues_count (:open_issues_count m)
     :owner {:login (get-in m [:owner :login])}}))

(defn guest-project-twitter-first-bytes []
  (let [first (first (:statuses (json/parse-bytes twitter-bytes)))]
    {:statuses [{:id (:id first)
                 :text (:text first)
                 :user {:screen_name (get-in first [:user :screen_name])}}]}))

(def github-schema
  [:map
   [:full_name :string]
   [:stargazers_count :int]
   [:open_issues_count :int]
   [:owner [:map [:login :string]]]])

(def twitter-first-schema
  [:map
   [:statuses
    [:cloffle/indexes
     [0 [:map
         [:id :long]
         [:text :string]
         [:user [:map [:screen_name :string]]]]]]]])

(defn guest-typed-jsonapi []
  (json/project jsonapi
                [:map
                 [:data [:map
                         [:id :string]
                         [:attributes [:map [:title :string]]]]]
                 [:meta [:map [:request-id :string]]]]))

(defn guest-typed-jsonapi-bytes []
  (json/project jsonapi-bytes
                [:map
                 [:data [:map
                         [:id :string]
                         [:attributes [:map [:title :string]]]]]
                 [:meta [:map [:request-id :string]]]]))

(defn guest-jackson-jsonapi-bytes []
  (json/project jsonapi-bytes
                [:map
                 [:data [:map
                         [:id :string]
                         [:attributes [:map [:title :string]]]]]
                 [:meta [:map [:request-id :string]]]]
                {:cloffle/backend :jackson}))

(defn guest-jackson3-jsonapi-bytes []
  (json/project jsonapi-bytes
                [:map
                 [:data [:map
                         [:id :string]
                         [:attributes [:map [:title :string]]]]]
                 [:meta [:map [:request-id :string]]]]
                {:cloffle/backend :jackson3}))

(defn guest-jackson3-jsonapi-truffle-bytes []
  (json/project jsonapi-bytes
                [:map
                 [:data [:map
                         [:id :string]
                         [:attributes [:map [:title :string]]]]]
                 [:meta [:map [:request-id :string]]]]
                {:cloffle/backend :jackson3 :cloffle/strings :truffle}))

(defn guest-typed-jsonapi-truffle-bytes []
  (json/project jsonapi-bytes
                [:map
                 [:data [:map
                         [:id :string]
                         [:attributes [:map [:title :string]]]]]
                 [:meta [:map [:request-id :string]]]]
                {:cloffle/strings :truffle}))

(defn guest-typed-github []
  (json/project github-json
                [:map
                 [:full_name :string]
                 [:stargazers_count :int]
                 [:open_issues_count :int]
                 [:owner [:map [:login :string]]]]))

(defn guest-typed-github-bytes []
  (json/project github-bytes
                [:map
                 [:full_name :string]
                 [:stargazers_count :int]
                 [:open_issues_count :int]
                 [:owner [:map [:login :string]]]]))

(defn guest-jackson-github-bytes []
  (json/project github-bytes
                [:map
                 [:full_name :string]
                 [:stargazers_count :int]
                 [:open_issues_count :int]
                 [:owner [:map [:login :string]]]]
                {:cloffle/backend :jackson}))

(defn guest-jackson3-github-bytes []
  (json/project github-bytes
                [:map
                 [:full_name :string]
                 [:stargazers_count :int]
                 [:open_issues_count :int]
                 [:owner [:map [:login :string]]]]
                {:cloffle/backend :jackson3}))

(defn guest-jackson3-github-truffle-bytes []
  (json/project github-bytes
                [:map
                 [:full_name :string]
                 [:stargazers_count :int]
                 [:open_issues_count :int]
                 [:owner [:map [:login :string]]]]
                {:cloffle/backend :jackson3 :cloffle/strings :truffle}))

(defn guest-typed-github-truffle-bytes []
  (json/project github-bytes
                [:map
                 [:full_name :string]
                 [:stargazers_count :int]
                 [:open_issues_count :int]
                 [:owner [:map [:login :string]]]]
                {:cloffle/strings :truffle}))

(defn guest-typed-github-buffer []
  (json/project github-buffer
                [:map
                 [:full_name :string]
                 [:stargazers_count :int]
                 [:open_issues_count :int]
                 [:owner [:map [:login :string]]]]))

(defn guest-typed-github-sum-bytes []
  (let [m (json/project github-bytes
                        [:map
                         [:stargazers_count :int]
                         [:open_issues_count :int]])]
    (+ (:stargazers_count m) (:open_issues_count m))))

(defn guest-jackson-github-sum-bytes []
  (let [m (json/project github-bytes
                        [:map
                         [:stargazers_count :int]
                         [:open_issues_count :int]]
                        {:cloffle/backend :jackson})]
    (+ (:stargazers_count m) (:open_issues_count m))))

(defn guest-jackson3-github-sum-bytes []
  (let [m (json/project github-bytes
                        [:map
                         [:stargazers_count :int]
                         [:open_issues_count :int]]
                        {:cloffle/backend :jackson3})]
    (+ (:stargazers_count m) (:open_issues_count m))))

(defn guest-typed-twitter-first []
  (json/project twitter-json
                [:map
                 [:statuses
                  [:cloffle/indexes
                   [0 [:map
                       [:id :long]
                       [:text :string]
                       [:user [:map [:screen_name :string]]]]]]]]))

(defn guest-typed-twitter-first-bytes []
  (json/project twitter-bytes
                [:map
                 [:statuses
                  [:cloffle/indexes
                   [0 [:map
                       [:id :long]
                       [:text :string]
                       [:user [:map [:screen_name :string]]]]]]]]))

(defn guest-jackson-twitter-first-bytes []
  (json/project twitter-bytes
                [:map
                 [:statuses
                  [:cloffle/indexes
                   [0 [:map
                       [:id :long]
                       [:text :string]
                       [:user [:map [:screen_name :string]]]]]]]]
                {:cloffle/backend :jackson}))

(defn guest-jackson3-twitter-first-bytes []
  (json/project twitter-bytes
                [:map
                 [:statuses
                  [:cloffle/indexes
                   [0 [:map
                       [:id :long]
                       [:text :string]
                       [:user [:map [:screen_name :string]]]]]]]]
                {:cloffle/backend :jackson3}))

(defn guest-jackson3-twitter-first-truffle-bytes []
  (json/project twitter-bytes
                [:map
                 [:statuses
                  [:cloffle/indexes
                   [0 [:map
                       [:id :long]
                       [:text :string]
                       [:user [:map [:screen_name :string]]]]]]]]
                {:cloffle/backend :jackson3 :cloffle/strings :truffle}))

(defn guest-jackson3-twitter-first-truffle-input []
  (json/project twitter-truffle
                [:map
                 [:statuses
                  [:cloffle/indexes
                   [0 [:map
                       [:id :long]
                       [:text :string]
                       [:user [:map [:screen_name :string]]]]]]]]
                {:cloffle/backend :jackson3 :cloffle/strings :truffle}))

(defn guest-typed-twitter-first-truffle-bytes []
  (json/project twitter-bytes
                [:map
                 [:statuses
                  [:cloffle/indexes
                   [0 [:map
                       [:id :long]
                       [:text :string]
                       [:user [:map [:screen_name :string]]]]]]]]
                {:cloffle/strings :truffle}))

(defn guest-typed-twitter-first-truffle-input []
  (json/project twitter-truffle
                [:map
                 [:statuses
                  [:cloffle/indexes
                   [0 [:map
                       [:id :long]
                       [:text :string]
                       [:user [:map [:screen_name :string]]]]]]]]
                {:cloffle/strings :truffle}))

(defn guest-typed-twitter-first-buffer []
  (json/project twitter-buffer
                [:map
                 [:statuses
                  [:cloffle/indexes
                   [0 [:map
                       [:id :long]
                       [:text :string]
                       [:user [:map [:screen_name :string]]]]]]]]))

(defn guest-typed-escaped []
  (json/project escaped [:map [:message :string] [:id :int]]))

(defn guest-unschemed-github-bytes []
  (json/project github-bytes))

(defn guest-typed-github-early-bytes []
  (json/project github-bytes [:map [:full_name :string]]))

(defn guest-jackson-github-early-bytes []
  (json/project github-bytes [:map [:full_name :string]]
                {:cloffle/backend :jackson}))

(defn guest-jackson3-github-early-bytes []
  (json/project github-bytes [:map [:full_name :string]]
                {:cloffle/backend :jackson3}))

(defn guest-typed-github-late-bytes []
  (json/project github-bytes [:map [:network_count :int]]))

(defn guest-jackson-github-late-bytes []
  (json/project github-bytes [:map [:network_count :int]]
                {:cloffle/backend :jackson}))

(defn guest-jackson3-github-late-bytes []
  (json/project github-bytes [:map [:network_count :int]]
                {:cloffle/backend :jackson3}))

(defn guest-json-schema-github-bytes []
  (json/project github-bytes
                {:type "object"
                 :required ["full_name" "stargazers_count" "open_issues_count" "owner"]
                 :properties {:full_name {:type "string"}
                              :stargazers_count {:type "integer"}
                              :open_issues_count {:type "integer"}
                              :owner {:type "object"
                                      :required ["login"]
                                      :properties {:login {:type "string"}}}}}))

(defn guest-json-schema-github-early-bytes []
  (json/project github-bytes
                {:type "object"
                 :required ["full_name"]
                 :properties {:full_name {:type "string"}}}))

(defn guest-json-schema-github-late-bytes []
  (json/project github-bytes
                {:type "object"
                 :required ["network_count"]
                 :properties {:network_count {:type "integer"}}}))

;; Same field sets as the guest-typed-* benchmarks above, addressed by JSON Pointer so they can be
;; compared against Jackson's two pointer paths.

(defn guest-select-github-bytes []
  (json/select github-bytes
               ["/full_name" "/stargazers_count" "/open_issues_count" "/owner/login"]))

;; Fractional and exponent literals, which no other payload here exercises.
(def doubles-json
  (net.javacrumbs.cloffle.benchmark.JsonParserBenchmark/fixture
   "json-parser-benchmark/data/doubles.json"))
(def doubles-bytes (.getBytes doubles-json "UTF-8"))

(defn guest-typed-doubles-bytes []
  (json/project doubles-bytes
                [:map
                 [:lat :double]
                 [:lon :double]
                 [:altitude :double]
                 [:speed :double]
                 [:heading :double]
                 [:accuracy :double]
                 [:temp_c :double]
                 [:humidity :double]]))

(defn guest-jackson3-doubles-bytes []
  (json/project doubles-bytes
                [:map
                 [:lat :double]
                 [:lon :double]
                 [:altitude :double]
                 [:speed :double]
                 [:heading :double]
                 [:accuracy :double]
                 [:temp_c :double]
                 [:humidity :double]]
                {:cloffle/backend :jackson3}))

(defn guest-select-jsonapi-bytes []
  (json/select jsonapi-bytes
               ["/data/id" "/data/attributes/title" "/meta/request-id"]))

(defn guest-jackson3-select-github-bytes []
  (json/select github-bytes
               ["/full_name" "/stargazers_count" "/open_issues_count" "/owner/login"]
               {:cloffle/backend :jackson3}))

(defn guest-jackson3-select-jsonapi-bytes []
  (json/select jsonapi-bytes
               ["/data/id" "/data/attributes/title" "/meta/request-id"]
               {:cloffle/backend :jackson3}))

;; SIMD stage-1 plus Cloffle's fixed projection trie. These intentionally mirror the custom and
;; Jackson3 legs so latency and allocation are directly comparable.
(defn guest-simdjson-jsonapi-bytes []
  (json/project jsonapi-bytes
                [:map
                 [:data [:map [:id :string]
                         [:attributes [:map [:title :string]]]]]
                 [:meta [:map [:request-id :string]]]]
                {:cloffle/backend :simdjson}))

(defn guest-simdjson-jsonapi-truffle-bytes []
  (json/project jsonapi-bytes
                [:map
                 [:data [:map [:id :string]
                         [:attributes [:map [:title :string]]]]]
                 [:meta [:map [:request-id :string]]]]
                {:cloffle/backend :simdjson :cloffle/strings :truffle}))

(defn guest-simdjson-github-bytes []
  (json/project github-bytes
                [:map [:full_name :string] [:stargazers_count :int]
                 [:open_issues_count :int] [:owner [:map [:login :string]]]]
                {:cloffle/backend :simdjson}))

(defn guest-simdjson-github-truffle-bytes []
  (json/project github-bytes
                [:map [:full_name :string] [:stargazers_count :int]
                 [:open_issues_count :int] [:owner [:map [:login :string]]]]
                {:cloffle/backend :simdjson :cloffle/strings :truffle}))

(defn guest-simdjson-github-sum-bytes []
  (let [m (json/project github-bytes
                        [:map [:stargazers_count :int] [:open_issues_count :int]]
                        {:cloffle/backend :simdjson})]
    (+ (:stargazers_count m) (:open_issues_count m))))

(defn guest-simdjson-github-early-bytes []
  (json/project github-bytes [:map [:full_name :string]]
                {:cloffle/backend :simdjson}))

(defn guest-simdjson-github-late-bytes []
  (json/project github-bytes [:map [:network_count :int]]
                {:cloffle/backend :simdjson}))

(defn guest-simdjson-twitter-first-bytes []
  (json/project twitter-bytes
                [:map [:statuses [:cloffle/indexes
                                  [0 [:map [:id :long] [:text :string]
                                      [:user [:map [:screen_name :string]]]]]]]]
                {:cloffle/backend :simdjson}))

(defn guest-simdjson-twitter-first-truffle-bytes []
  (json/project twitter-bytes
                [:map [:statuses [:cloffle/indexes
                                  [0 [:map [:id :long] [:text :string]
                                      [:user [:map [:screen_name :string]]]]]]]]
                {:cloffle/backend :simdjson :cloffle/strings :truffle}))

(defn guest-simdjson-twitter-first-truffle-input []
  (json/project twitter-truffle
                [:map [:statuses [:cloffle/indexes
                                  [0 [:map [:id :long] [:text :string]
                                      [:user [:map [:screen_name :string]]]]]]]]
                {:cloffle/backend :simdjson :cloffle/strings :truffle}))

(defn guest-simdjson-doubles-bytes []
  (json/project doubles-bytes
                [:map [:lat :double] [:lon :double] [:altitude :double] [:speed :double]
                 [:heading :double] [:accuracy :double] [:temp_c :double] [:humidity :double]]
                {:cloffle/backend :simdjson}))

(defn guest-simdjson-select-github-bytes []
  (json/select github-bytes
               ["/full_name" "/stargazers_count" "/open_issues_count" "/owner/login"]
               {:cloffle/backend :simdjson}))

(defn guest-simdjson-select-jsonapi-bytes []
  (json/select jsonapi-bytes
               ["/data/id" "/data/attributes/title" "/meta/request-id"]
               {:cloffle/backend :simdjson}))

(def placeholder-json
  (net.javacrumbs.cloffle.benchmark.JsonParserBenchmark/fixture
   "json-parser-benchmark/data/jsonplaceholder-post-1.json"))
(def placeholder-bytes (.getBytes placeholder-json "UTF-8"))

(defn guest-parse-lookup-placeholder []
  (:title (json/parse-string placeholder-json)))

(defn guest-project-placeholder-bytes []
  (let [m (json/parse-bytes placeholder-bytes)]
    {:id (:id m) :userId (:userId m) :title (:title m)}))

(defn guest-typed-placeholder-bytes []
  (json/project placeholder-bytes
                [:map [:id :int] [:userId :int] [:title :string]]))

(defn guest-jackson3-placeholder-bytes []
  (json/project placeholder-bytes
                [:map [:id :int] [:userId :int] [:title :string]]
                {:cloffle/backend :jackson3}))

(defn guest-simdjson-placeholder-bytes []
  (json/project placeholder-bytes
                [:map [:id :int] [:userId :int] [:title :string]]
                {:cloffle/backend :simdjson}))

(defn guest-select-placeholder-bytes []
  (json/select placeholder-bytes ["/id" "/userId" "/title"]))

(defn guest-jackson3-select-placeholder-bytes []
  (json/select placeholder-bytes ["/id" "/userId" "/title"]
               {:cloffle/backend :jackson3}))

(defn guest-simdjson-select-placeholder-bytes []
  (json/select placeholder-bytes ["/id" "/userId" "/title"]
               {:cloffle/backend :simdjson}))

(def popular-apis-json
  (net.javacrumbs.cloffle.benchmark.JsonParserBenchmark/fixture
   "json-parser-benchmark/data/popular-apis-composite.json"))
(def popular-apis-bytes (.getBytes popular-apis-json "UTF-8"))

(def popular-apis-schema
  "Shared shape for REPL/docs. Benchmark guests must inline this literal in `(json/project …)` so
   `ExprToBytecodeJsonTypedProject` sees a constant schema (a Var reference uses the slow path)."
  [:map
   [:id :string]
   [:livemode :boolean]
   [:created :long]
   [:data [:map
           [:type :string]
           [:id :string]
           [:attributes [:map
                         [:title :string]
                         [:amount_cents :int]
                         [:fee_rate :double]]]]]
   [:repository [:map
                 [:full_name :string]
                 [:stargazers_count :int]
                 [:private :boolean]]]
   [:geo [:map [:lat :double] [:lon :double]]]
   [:line_items
    [:cloffle/indexes
     [0 [:map [:sku :string] [:quantity :int] [:unit_amount :double]]]
     [1 [:map [:sku :string] [:quantity :int] [:unit_amount :double]]]]]
   [:meta [:map [:request_id :string] [:version :string]]]])

(defn guest-typed-popular-apis-bytes []
  (json/project popular-apis-bytes
                [:map
                 [:id :string]
                 [:livemode :boolean]
                 [:created :long]
                 [:data [:map
                         [:type :string]
                         [:id :string]
                         [:attributes [:map
                                       [:title :string]
                                       [:amount_cents :int]
                                       [:fee_rate :double]]]]]
                 [:repository [:map
                               [:full_name :string]
                               [:stargazers_count :int]
                               [:private :boolean]]]
                 [:geo [:map [:lat :double] [:lon :double]]]
                 [:line_items
                  [:cloffle/indexes
                   [0 [:map [:sku :string] [:quantity :int] [:unit_amount :double]]]
                   [1 [:map [:sku :string] [:quantity :int] [:unit_amount :double]]]]]
                 [:meta [:map [:request_id :string] [:version :string]]]]))

(defn guest-jackson3-popular-apis-bytes []
  (json/project popular-apis-bytes
                [:map
                 [:id :string]
                 [:livemode :boolean]
                 [:created :long]
                 [:data [:map
                         [:type :string]
                         [:id :string]
                         [:attributes [:map
                                       [:title :string]
                                       [:amount_cents :int]
                                       [:fee_rate :double]]]]]
                 [:repository [:map
                               [:full_name :string]
                               [:stargazers_count :int]
                               [:private :boolean]]]
                 [:geo [:map [:lat :double] [:lon :double]]]
                 [:line_items
                  [:cloffle/indexes
                   [0 [:map [:sku :string] [:quantity :int] [:unit_amount :double]]]
                   [1 [:map [:sku :string] [:quantity :int] [:unit_amount :double]]]]]
                 [:meta [:map [:request_id :string] [:version :string]]]]
                {:cloffle/backend :jackson3}))

(defn- mix
  ^long [^long h x]
  (unchecked-add (unchecked-multiply h 31) (long (hash x))))

(defn guest-typed-placeholder-consume []
  (let [m (json/project placeholder-bytes
                        [:map [:id :int] [:userId :int] [:title :string]])]
    (-> 1 (mix (:id m)) (mix (:userId m)) (mix (:title m)))))

(defn guest-typed-jsonapi-consume []
  (let [m (json/project jsonapi-bytes
                        [:map
                         [:data [:map
                                 [:id :string]
                                 [:attributes [:map [:title :string]]]]]
                         [:meta [:map [:request-id :string]]]])]
    (-> 1
        (mix (get-in m [:data :id]))
        (mix (get-in m [:data :attributes :title]))
        (mix (get-in m [:meta :request-id])))))

(defn guest-typed-github-consume []
  (let [m (json/project github-bytes
                        [:map
                         [:full_name :string]
                         [:stargazers_count :int]
                         [:open_issues_count :int]
                         [:owner [:map [:login :string]]]])]
    (-> 1
        (mix (:full_name m))
        (mix (:stargazers_count m))
        (mix (:open_issues_count m))
        (mix (get-in m [:owner :login])))))

(defn guest-typed-twitter-first-consume []
  (let [m (json/project twitter-bytes
                        [:map
                         [:statuses
                          [:cloffle/indexes
                           [0 [:map
                               [:id :long]
                               [:text :string]
                               [:user [:map [:screen_name :string]]]]]]]])
        first (nth (:statuses m) 0)]
    (-> 1
        (mix (:id first))
        (mix (:text first))
        (mix (get-in first [:user :screen_name])))))

(defn guest-typed-popular-apis-consume []
  (let [m (json/project popular-apis-bytes
                        [:map
                         [:id :string]
                         [:livemode :boolean]
                         [:created :long]
                         [:data [:map
                                 [:type :string]
                                 [:id :string]
                                 [:attributes [:map
                                               [:title :string]
                                               [:amount_cents :int]
                                               [:fee_rate :double]]]]]
                         [:repository [:map
                                       [:full_name :string]
                                       [:stargazers_count :int]
                                       [:private :boolean]]]
                         [:geo [:map [:lat :double] [:lon :double]]]
                         [:line_items
                          [:cloffle/indexes
                           [0 [:map [:sku :string] [:quantity :int] [:unit_amount :double]]]
                           [1 [:map [:sku :string] [:quantity :int] [:unit_amount :double]]]]]
                         [:meta [:map [:request_id :string] [:version :string]]]])
        data (:data m)
        attrs (:attributes data)
        repo (:repository m)
        geo (:geo m)
        items (:line_items m)
        i0 (nth items 0)
        i1 (nth items 1)
        meta (:meta m)]
    (-> 1
        (mix (:id m))
        (mix (:livemode m))
        (mix (:created m))
        (mix (:type data))
        (mix (:id data))
        (mix (:title attrs))
        (mix (:amount_cents attrs))
        (mix (:fee_rate attrs))
        (mix (:full_name repo))
        (mix (:stargazers_count repo))
        (mix (:private repo))
        (mix (:lat geo))
        (mix (:lon geo))
        (mix (:sku i0))
        (mix (:quantity i0))
        (mix (:unit_amount i0))
        (mix (:sku i1))
        (mix (:quantity i1))
        (mix (:unit_amount i1))
        (mix (:request_id meta))
        (mix (:version meta)))))
