(ns bench.snippet.hiccup-normalize-small)

(defn bench []
    (let [tag-name "a"
        content-str "click"
        elem [tag-name {:class "btn" :href "/home"} content-str]
        [tag & items] elem
        [attrs content] (if (map? (first items))
                          [(first items) (first (rest items))]
                          [nil (first items)])]
    (if (and (= tag tag-name)
             (= (:href attrs) "/home"))
      content
      nil)))
