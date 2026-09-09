(ns test.guest.dissoc-transition)
(defn cached-incoming-dissoc [m]
  (let [updated (dissoc m :b)]
    [(:b updated)
     (:a updated)
     (:c updated)
     (count updated)
     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))
(defn multi-step-dissoc [m]
  (let [updated (-> m (dissoc :c) (dissoc :a))]
    [(:a updated)
     (:b updated)
     (:c updated)
     (count updated)
     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))
(defn demote-nine [m]
  (let [updated (dissoc m :p8)]
    [(:p8 updated)
     (count updated)
     (instance? clojure.lang.PersistentShapeMap updated)
     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))
