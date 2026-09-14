;; PROVISIONAL: `nth` puts a boxed Long index on the measured path, so this snippet's number is
;; dominated by index boxing rather than by the lowering layer. Revisit after primitives are
;; specialized; until then treat it as a workload sample, not a benchmark.

(ns bench.snippet.hiccup-normalize)

(defn bench []
    (let [tag-name "a"
        content-str "click"
        elem [tag-name {:class "btn" :href "/home"} content-str]
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
