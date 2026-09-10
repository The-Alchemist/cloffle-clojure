(ns test.guest.assoc-transition)
(defn cached-incoming-assoc [m v]
  (let [updated (assoc m :transition-added v)]
    [(:transition-added updated)
     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))
(defn promote-eight [v]
  (let [updated (assoc {:p0 0 :p1 1 :p2 2 :p3 3 :p4 4 :p5 5 :p6 6 :p7 7}
                       :transition-ninth v)]
    [(:transition-ninth updated)
     (count updated)
     (instance? clojure.lang.PersistentShapeMap16 updated)
     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))
(defn rewrite-sixteen [m v]
  (let [updated (assoc m :k0 v)]
    [(:k0 updated)
     (:k15 updated)
     (count updated)
     (instance? clojure.lang.PersistentShapeMap16 updated)
     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))

(defn insert-twelve [m v]
  (let [updated (assoc m :status v)]
    [(:status updated)
     (count updated)
     (instance? clojure.lang.PersistentShapeMap16 updated)
     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))

(defn promote-seventeen [m v]
  (let [updated (assoc m :overflow v)]
    [(:overflow updated)
     (count updated)
     (instance? clojure.lang.PersistentHashMap updated)
     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))
