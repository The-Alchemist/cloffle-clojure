;; Isolates the slow path from hiccup-normalize: assemble [tag attrs content] from locals,
;; then re-read with (nth norm 0/1/2). No elem / destructure / instance? parsing.

(ns bench.snippet.norm-tuple-nth)

(defn bench []
    (let [tag "a"
        attrs {:class "btn" :href "/home"}
        content "click"
        norm [tag attrs content]
        final-tag (nth norm 0)
        final-attrs (nth norm 1)
        final-content (nth norm 2)]
    (if (and (= final-tag tag)
             (= (:href final-attrs) "/home"))
      final-content
      nil)))
