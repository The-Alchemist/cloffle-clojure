(let [make (fn []
             {:data {:type "articles"
                     :id "article-101"
                     :attributes {:title "Shape maps" :slug "shape-maps" :status :draft
                                  :author "Avery" :locale "en-US" :category "runtime"}
                     :relationships {:author {:type "people" :id "person-7"}}
                     :links {:self "/articles/article-101"}}
              :included {:type "people" :id "person-7" :name "Avery"}
              :meta {:request-id "req-101" :version "v1"}})
      enrich (fn [document]
               (let [data (:data document)
                     attributes (assoc (assoc (:attributes data) :status :published) :summary "ok")]
                 (assoc document :data (assoc data :attributes attributes))))
      consume (fn [document]
                (let [data (:data document)
                      attributes (:attributes data)
                      included (:included document)]
                  (if (and (= (:status attributes) :published)
                           (= (:slug attributes) "shape-maps")
                           (= (:id included) "person-7")
                           (= (:id (:author (:relationships data))) "person-7"))
                    (:summary attributes)
                    nil)))]
  (consume (enrich (make))))
