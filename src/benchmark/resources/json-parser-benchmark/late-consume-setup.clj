#_{:clj-kondo/ignore [:unresolved-namespace :unresolved-symbol]}
(defn guest-typed-twitter-late-consume []
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
                          {:cloffle/strings :truffle})
          last-status (nth (:statuses m) 99)
          meta (:search_metadata m)]
      (-> 1
          (mix (:id last-status))
          (mix (:text last-status))
          (mix (get-in last-status [:user :screen_name]))
          (mix (:count meta))
          (mix (:completed_in meta))
          (mix (:query meta)))))

;; String-leaf ladder for attributing decode allocation. :cloffle/strings :truffle keeps UTF-8
;; views; (hash x) / mix uses TruffleString HashCodeNode (no toJavaString). The scan still walks
;; the whole document in every arm, so only the leaf set varies. -1str keeps the :user submap so
;; its shape matches the 3-string baseline exactly; -0str additionally drops that submap.
#_{:clj-kondo/ignore [:unresolved-namespace :unresolved-symbol]}
(defn guest-typed-twitter-late-consume-1str []
    (let [m (json/project twitter-bytes
                          [:map
                           [:statuses
                            [:cloffle/indexes
                             [99 [:map
                                  [:id :long]
                                  [:user [:map [:screen_name :string]]]]]]]
                           [:search_metadata
                            [:map
                             [:count :int]
                             [:completed_in :double]]]]
                          {:cloffle/strings :truffle})
          last-status (nth (:statuses m) 99)
          meta (:search_metadata m)]
      (-> 1
          (mix (:id last-status))
          (mix (get-in last-status [:user :screen_name]))
          (mix (:count meta))
          (mix (:completed_in meta)))))

#_{:clj-kondo/ignore [:unresolved-namespace :unresolved-symbol]}
(defn guest-typed-twitter-late-consume-0str []
    (let [m (json/project twitter-bytes
                          [:map
                           [:statuses
                            [:cloffle/indexes
                             [99 [:map
                                  [:id :long]]]]]
                           [:search_metadata
                            [:map
                             [:count :int]
                             [:completed_in :double]]]])
          last-status (nth (:statuses m) 99)
          meta (:search_metadata m)]
      (-> 1
          (mix (:id last-status))
          (mix (:count meta))
          (mix (:completed_in meta)))))
