;; Loaded after setup.clj. Kept as a distinct Source so appended scanner A/B
;; guests cannot be hidden by a cached compilation of the long base setup source.
;; --- PEA scanner-variant guests (:cloffle/scanner) ----------------------------
;; Schema literals must stay inline so the bytecode emitter sees a constant plan.
;; twitterLate forces a full traversal (statuses[99] + search_metadata).

#_{:clj-kondo/ignore [:unresolved-namespace :unresolved-symbol]}
(def guest-typed-twitter-late-consume
  (fn guest-typed-twitter-late-consume []
  (let [m (json/project twitter-bytes
                        [:map
                         [:statuses
                          [:cloffle/indexes
                           [99 [:map
                                [:id :long]
                                [:text :string]
                                [:user [:map [:screen_name :string]]]]]]]
                         [:search_metadata
                          [:map
                           [:count :int]
                           [:completed_in :double]
                           [:query :string]]]])
        first (nth (:statuses m) 0)
        meta (:search_metadata m)]
    (-> 1
        (mix (:id first))
        (mix (:text first))
        (mix (get-in first [:user :screen_name]))
        (mix (:count meta))
        (mix (:completed_in meta))
        (mix (:query meta))))))

#_{:clj-kondo/ignore [:unresolved-namespace :unresolved-symbol]}
(def guest-typed-twitter-late-consume-v1
  (fn guest-typed-twitter-late-consume-v1 []
  (let [m (json/project twitter-bytes
                        [:map
                         [:statuses
                          [:cloffle/indexes
                           [99 [:map
                                [:id :long]
                                [:text :string]
                                [:user [:map [:screen_name :string]]]]]]]
                         [:search_metadata
                          [:map
                           [:count :int]
                           [:completed_in :double]
                           [:query :string]]]]
                        {:cloffle/scanner :cold-error})
        first (nth (:statuses m) 0)
        meta (:search_metadata m)]
    (-> 1
        (mix (:id first))
        (mix (:text first))
        (mix (get-in first [:user :screen_name]))
        (mix (:count meta))
        (mix (:completed_in meta))
        (mix (:query meta))))))

#_{:clj-kondo/ignore [:unresolved-namespace :unresolved-symbol]}
(def guest-typed-twitter-late-consume-v2
  (fn guest-typed-twitter-late-consume-v2 []
  (let [m (json/project twitter-bytes
                        [:map
                         [:statuses
                          [:cloffle/indexes
                           [99 [:map
                                [:id :long]
                                [:text :string]
                                [:user [:map [:screen_name :string]]]]]]]
                         [:search_metadata
                          [:map
                           [:count :int]
                           [:completed_in :double]
                           [:query :string]]]]
                        {:cloffle/scanner :static-skip})
        first (nth (:statuses m) 0)
        meta (:search_metadata m)]
    (-> 1
        (mix (:id first))
        (mix (:text first))
        (mix (get-in first [:user :screen_name]))
        (mix (:count meta))
        (mix (:completed_in meta))
        (mix (:query meta))))))

#_{:clj-kondo/ignore [:unresolved-namespace :unresolved-symbol]}
(def guest-typed-twitter-late-consume-v3
  (fn guest-typed-twitter-late-consume-v3 []
  (let [m (json/project twitter-bytes
                        [:map
                         [:statuses
                          [:cloffle/indexes
                           [99 [:map
                                [:id :long]
                                [:text :string]
                                [:user [:map [:screen_name :string]]]]]]]
                         [:search_metadata
                          [:map
                           [:count :int]
                           [:completed_in :double]
                           [:query :string]]]]
                        {:cloffle/scanner :bytes-only})
        first (nth (:statuses m) 0)
        meta (:search_metadata m)]
    (-> 1
        (mix (:id first))
        (mix (:text first))
        (mix (get-in first [:user :screen_name]))
        (mix (:count meta))
        (mix (:completed_in meta))
        (mix (:query meta))))))

#_{:clj-kondo/ignore [:unresolved-namespace :unresolved-symbol]}
(def guest-typed-popular-apis-consume-v2
  (fn guest-typed-popular-apis-consume-v2 []
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
                         [:meta [:map [:request_id :string] [:version :string]]]]
                        {:cloffle/scanner :static-skip})
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
        (mix (:version meta))))))
