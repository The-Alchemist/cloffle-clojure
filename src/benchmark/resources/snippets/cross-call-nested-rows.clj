(ns bench.snippet.cross-call-nested-rows)

(defn make []
    [{:id :one :n 1 :meta {:ok true :src :db}}
                {:id :two :n 2 :meta {:ok true :src :db}}
                {:id :three :n 3 :meta {:ok true :src :cache}}
                {:id :four :n 4 :meta {:ok true :src :db}}])

(defn enrich [rows]
    (let [[a b c d] rows
                       a2 (assoc a :n 10)
                       d2 (assoc d :meta (assoc (:meta d) :ok false))]
                   [a2 b c d2]))

(defn consume [rows]
    (let [[a b c d] rows]
                    (if (and (= (:id a) :one)
                             (= (:n a) 10)
                             (= (:id b) :two)
                             (= (:n c) 3)
                             (= (:src (:meta c)) :cache)
                             (= (:ok (:meta d)) false))
                      (:id d)
                      nil)))

(defn bench []
  (consume (enrich (make))))
