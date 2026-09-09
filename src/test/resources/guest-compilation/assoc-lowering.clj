(ns test.guest.assoc-lowering)

;; Stable-shape receiver: every call sees the same MapShape, so the KeywordAssoc
;; transition cache should stay on its first entry and doShapeMap should stay live.
(defn stable-assoc [v]
  (:b (assoc {:a :v1 :b :v2 :c :v3} :b v)))

;; Deliberately shape-polymorphic beyond the transition cache limit of 4, to prove
;; the cache exhausts into doShapeMapGeneric rather than silently misbehaving.
(defn polymorphic-assoc [m v]
  (:b (assoc m :b v)))

;; (get m :k) and (get m :k default) must lower to the same operations that (:k m) uses.
(defn literal-get [m]
  (get m :b))

(defn literal-get-default [m]
  (get m :missing :fallback))

;; A computed key cannot lower; this must stay on the Var path.
(defn computed-get [m k]
  (get m k))

;; Stable-shape dissoc: the KeywordDissoc transition cache should stay on one entry.
(defn stable-dissoc [m]
  (:a (dissoc m :b)))

;; PersistentShapeMap16 receiver, exercising the 9->8 demotion back into PersistentShapeMap.
(defn dissoc16 [m]
  (:k0 (dissoc m :k4)))

;; 10-key PersistentShapeMap16: Dissoc16Transition is null (only count 9 is planned).
;; The lowering must fall through to doShapeMap16Generic rather than NPE.
(defn dissoc16-10 [m]
  (:k0 (dissoc m :k4)))

;; A computed key cannot lower; this must stay on the Var path.
(defn computed-dissoc [m k]
  (:a (dissoc m k)))
