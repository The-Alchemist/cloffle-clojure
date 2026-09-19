;; Loaded after setup.clj. Kept as a distinct Source because guests appended to the
;; long base setup source can be hidden by a cached compilation of it.
;; Schema literals must stay inline so the bytecode emitter sees a constant plan.
;; twitterLate forces a full traversal (statuses[99] + search_metadata at the tail),
;; and reads back the projected index so the decoded values are actually consumed.

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
          last-status (nth (:statuses m) 99)
          meta (:search_metadata m)]
      (-> 1
          (mix (:id last-status))
          (mix (:text last-status))
          (mix (get-in last-status [:user :screen_name]))
          (mix (:count meta))
          (mix (:completed_in meta))
          (mix (:query meta))))))
