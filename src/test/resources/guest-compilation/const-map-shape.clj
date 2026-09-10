(ns test.guest.const-map-shape)

(defn nested-const-headers [body]
  {:status 200
   :headers {:content-type "text/plain" :server "cloffle"}
   :body body})

(defn all-const-nested []
  {:status 200 :headers {:content-type "text/plain"} :body "ok"})

(defn const-int-key []
  (let [m {1 :a}] (get m 1)))
