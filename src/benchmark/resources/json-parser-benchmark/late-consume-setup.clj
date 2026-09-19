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
                             [:query :string]]]])
          last-status (nth (:statuses m) 99)
          meta (:search_metadata m)]
      (-> 1
          (mix (:id last-status))
          (mix (:text last-status))
          (mix (get-in last-status [:user :screen_name]))
          (mix (:count meta))
          (mix (:completed_in meta))
          (mix (:query meta)))))
