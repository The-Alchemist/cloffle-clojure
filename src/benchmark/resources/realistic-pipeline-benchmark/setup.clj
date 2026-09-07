(defn wrap-auth [handler]
  (fn [req]
    (if (= (get req :auth-token) "secret-token")
      (handler (assoc req :user-id 42 :authenticated? true :role :admin))
      {:status 401 :body "Unauthorized"})))

(defn wrap-params [handler]
  (fn [req]
    (let [query (get req :query-string "")
          page (if (= query "page=2") 2 1)]
      (handler (assoc req :page page :limit 50)))))

(defn wrap-enrich [handler]
  (fn [req]
    (handler (assoc req :tenant-id 1001 :trace-id "trace-xyz" :request-time 1700000000))))

(defn api-handler [req]
  (if (get req :authenticated?)
    {:status 200
     :user-id (get req :user-id)
     :tenant-id (get req :tenant-id)
     :page (get req :page)
     :body "success"}
    {:status 403 :body "Forbidden"}))

(def ring-app (-> api-handler wrap-enrich wrap-params wrap-auth))

(def ring-req-shape {:uri "/api/items" :method :get :auth-token "secret-token"
                     :query-string "page=2" :remote-addr "127.0.0.1" :host "example.com"
                     :scheme :https :content-type "application/json"})

(def ring-req-hash {:uri "/api/items" :method :get :auth-token "secret-token"
                    :query-string "page=2" :remote-addr "127.0.0.1" :host "example.com"
                    :scheme :https :content-type "application/json"
                    :k8 8 :k9 9 :k10 10 :k11 11 :k12 12 :k13 13 :k14 14 :k15 15 :k16 16 :k17 17})

(defn process-order [order]
  (let [status (get order :status)
        total (get order :total)]
    (cond
      (= status :pending)
      (if (> total 1000)
        (assoc order :status :manual-review :priority :high :audit-tag :large-order)
        (assoc order :status :auto-approved :priority :normal :audit-tag :standard))
      (= status :in-review)
      (assoc order :status :approved :approved-by :risk-engine :review-score 95)
      (= status :flagged)
      (assoc order :status :rejected :reason :fraud-suspicion :lock-account? true)
      :else
      (assoc order :status :unknown :error-code -1))))

(def order-shape {:order-id 501 :customer-id 12 :status :pending :total 1500
                  :currency :USD :items-count 3 :payment-method :credit-card})

(def order-hash {:order-id 501 :customer-id 12 :status :pending :total 1500
                 :currency :USD :items-count 3 :payment-method :credit-card
                 :k7 7 :k8 8 :k9 9 :k10 10 :k11 11 :k12 12 :k13 13 :k14 14 :k15 15 :k16 16 :k17 17})

(defn aggregate-metrics [initial-state n]
  (loop [i 0
         state initial-state]
    (if (< i n)
      (recur (unchecked-inc i)
             (assoc state
                    :count (unchecked-inc (get state :count))
                    :sum (+ (get state :sum) i)
                    :last-val i))
      state)))

(def acc-shape {:count 0 :sum 0 :last-val 0 :min 0 :max 1000 :active true :source :sensor-1 :window 60})

(def acc-hash {:count 0 :sum 0 :last-val 0 :min 0 :max 1000 :active true :source :sensor-1 :window 60
               :k8 8 :k9 9 :k10 10 :k11 11 :k12 12 :k13 13 :k14 14 :k15 15 :k16 16 :k17 17})

(defn step1-parse [m] (assoc m :parsed-val (unchecked-inc (get m :raw-val 0))))
(defn step2-validate [m] (if (> (get m :parsed-val) 0) (assoc m :valid? true) (assoc m :valid? false)))
(defn step3-enrich [m] (assoc m :enriched-tag :tier-gold :discount 0.15))
(defn step4-compute [m] (assoc m :final-score (* (get m :parsed-val) 10)))
(defn step5-finalize [m] (assoc m :completed? true :status :done))

(defn composed-workload [m]
  (-> m
      step1-parse
      step2-validate
      step3-enrich
      step4-compute
      step5-finalize))

(def comp-shape {:raw-val 41 :user-id "u102" :tenant "acme" :region :us-east :priority :high :flag true})

(def comp-hash {:raw-val 41 :user-id "u102" :tenant "acme" :region :us-east :priority :high :flag true
                :k6 6 :k7 7 :k8 8 :k9 9 :k10 10 :k11 11 :k12 12 :k13 13 :k14 14 :k15 15 :k16 16 :k17 17})
