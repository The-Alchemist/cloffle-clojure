# Clojure vs Cloffle Performance Comparison

**Date:** 2026-09-07  
**Environment:** Mac OS X (aarch64), Java 25.0.4.1  

Built-in guest samples, compared via direct `IFn.invoke`.

### Summary

| Sample | Clojure (ops/sec) | Cloffle (ops/sec) | Speedup (x) | Clojure p95 (ns) | Cloffle p95 (ns) | Clojure alloc (B/op) | Cloffle alloc (B/op) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `consume-assoc` | 170M | 19.8M | 0.12 | 42 | 84 | 72 | 320 |
| `array-map-lookup` | 406M | 235M | 0.58 | 42 | 42 | 0 | 0 |
| `keyword-invoke` | 333M | 210M | 0.63 | 42 | 42 | 0 | 0 |
| `nested-get-in` | 51.3M | 4.88M | 0.10 | 42 | 291 | 64 | 1,224 |
| `assoc-pipeline` | 180M | 19.2M | 0.11 | 42 | 84 | 80 | 320 |
| `ephemeral-pipeline` | 169M | 20.7M | 0.12 | 42 | 84 | 72 | 320 |
| `ephemeral-insert` | 117M | 20.5M | 0.18 | 42 | 84 | 72 | 320 |
| `ephemeral-promote8` | 4.97M | 16.1M | 3.24 | 250 | 125 | 648 | 384 |
| `ephemeral-dissoc` | 102M | 25.1M | 0.25 | 42 | 84 | 64 | 288 |
| `tuple-destructure` | 467M | 235M | 0.50 | 42 | 42 | 0 | 0 |
| `lazy-seq-first` | 70.0M | 48.4M | 0.69 | 42 | 42 | 136 | 168 |
| `lazy-seq-vec-first` | 67.1M | 47.7M | 0.71 | 42 | 42 | 136 | 168 |
| `tuple2-transform` | 470M | 262M | 0.56 | 42 | 42 | 0 | 0 |
| `ring-response` | 33.1M | 7.06M | 0.21 | 83 | 208 | 208 | 864 |
| `hiccup-normalize` | 264M | 259M | 0.98 | 42 | 42 | 0 | 0 |
| `kwargs-destructure` | 316M | 233M | 0.74 | 42 | 42 | 0 | 0 |
| `middleware-pipeline` | 30.5M | 8.47M | 0.28 | 83 | 167 | 184 | 832 |
| `cond-option-pipeline` | 42.9M | 6.48M | 0.15 | 42 | 209 | 208 | 896 |
| `event-enrich` | 8.06M | 13.5M | 1.67 | 167 | 125 | 416 | 384 |
| `event-sanitize` | 53.1M | 13.6M | 0.26 | 42 | 125 | 152 | 480 |
| `fixed-str2` | 80.1M | 160M | 2.00 | 42 | 42 | 168 | 64 |

_Speedup (x) is Cloffle ÷ Clojure throughput. Latency columns share one unit chosen from the largest p95 across all samples._

### consume-assoc

```clojure
(let [m {:a :v1, :b :v2, :c :v3}] (:a (assoc m :b :v999)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 170M | 19.8M | 0.12x |
| **p50 latency (ns)** | 0 | 42 | - |
| **p95 latency (ns)** | 42 | 84 | 2.00x |
| **Allocation (B/op)** | 72 | 320 | 4.44x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### array-map-lookup

```clojure
(get {:a :v1 :b :v2 :c :v3} :b)
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 406M | 235M | 0.58x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### keyword-invoke

```clojure
(:b {:a :v1 :b :v2 :c :v3})
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 333M | 210M | 0.63x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### nested-get-in

```clojure
(get-in {:user {:profile {:name "Alice"}}} [:user :profile :name])
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 51.3M | 4.88M | 0.10x |
| **p50 latency (ns)** | 41 | 208 | 5.07x |
| **p95 latency (ns)** | 42 | 291 | 6.93x |
| **Allocation (B/op)** | 64 | 1,224 | 19.13x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### assoc-pipeline

```clojure
(get (assoc {:a :v1 :b :v2 :c :v3} :status :active) :status)
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 180M | 19.2M | 0.11x |
| **p50 latency (ns)** | 0 | 83 | - |
| **p95 latency (ns)** | 42 | 84 | 2.00x |
| **Allocation (B/op)** | 80 | 320 | 4.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### ephemeral-pipeline

```clojure
(let [m {:a "initial" :b :v2 :c :v3}]
  (:a (assoc m :a "replacement")))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 169M | 20.7M | 0.12x |
| **p50 latency (ns)** | 0 | 42 | - |
| **p95 latency (ns)** | 42 | 84 | 2.00x |
| **Allocation (B/op)** | 72 | 320 | 4.44x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### ephemeral-insert

```clojure
(let [m {:a :v1 :b :v2}
      m2 (assoc m :c :v3)]
  (if (= (:a m2) :v1)
    (:c m2)
    nil))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 117M | 20.5M | 0.18x |
| **p50 latency (ns)** | 0 | 83 | - |
| **p95 latency (ns)** | 42 | 84 | 2.00x |
| **Allocation (B/op)** | 72 | 320 | 4.44x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### ephemeral-promote8

```clojure
(let [m {:p0 :v0 :p1 :v1 :p2 :v2 :p3 :v3 :p4 :v4 :p5 :v5 :p6 :v6 :p7 :v7}
      m2 (assoc m :p8 :v8)]
  (if (= (:p0 m2) :v0)
    (:p8 m2)
    nil))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 4.97M | 16.1M | 3.24x |
| **p50 latency (ns)** | 208 | 83 | 0.40x |
| **p95 latency (ns)** | 250 | 125 | 0.50x |
| **Allocation (B/op)** | 648 | 384 | 0.59x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### ephemeral-dissoc

```clojure
(let [m {:a :v1 :b :v3 :c :v3}
      m2 (dissoc m :b)]
  (if (= (:a m2) :v1)
    (:c m2)
    nil))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 102M | 25.1M | 0.25x |
| **p50 latency (ns)** | 0 | 42 | - |
| **p95 latency (ns)** | 42 | 84 | 2.00x |
| **Allocation (B/op)** | 64 | 288 | 4.50x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### tuple-destructure

```clojure
(let [[a b] [:first :second]]
  (if (= a :first)
    b
    nil))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 467M | 235M | 0.50x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### lazy-seq-first

```clojure
(first (lazy-seq [:first]))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 70.0M | 48.4M | 0.69x |
| **p50 latency (ns)** | 41 | 42 | 1.02x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 136 | 168 | 1.24x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### lazy-seq-vec-first

```clojure
(first (lazy-seq [:first]))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 67.1M | 47.7M | 0.71x |
| **p50 latency (ns)** | 41 | 42 | 1.02x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 136 | 168 | 1.24x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### tuple2-transform

```clojure
(let [[a b] [:first :second]
      [c d] [b a]]
  c)
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 470M | 262M | 0.56x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### ring-response

```clojure
(let [resp {:status :ok :headers {:content-type "text/plain"} :body "ok"}
      resp2 (assoc resp :headers (assoc (:headers resp) :server "cloffle"))
      resp3 (assoc resp2 :status :created)
      {:keys [status headers body]} resp3]
  (if (and (= status :created)
           (= (:server headers) "cloffle")
           (= (:content-type headers) "text/plain"))
    body
    nil))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 33.1M | 7.06M | 0.21x |
| **p50 latency (ns)** | 42 | 166 | 3.95x |
| **p95 latency (ns)** | 83 | 208 | 2.51x |
| **Allocation (B/op)** | 208 | 864 | 4.15x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### hiccup-normalize

```clojure
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
    nil))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 264M | 259M | 0.98x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### kwargs-destructure

```clojure
(let [opts {:method :post :timeout "500ms"}
      {:keys [method timeout] :or {method :get timeout "1000ms"}} opts]
  (if (= method :post) timeout "none"))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 316M | 233M | 0.74x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### middleware-pipeline

```clojure
(let [req {:uri "/api/data" :request-method :post :headers {:content-type "application/json"} :body "test-payload"}
      req2 (assoc req :params {:query "search"})
      req3 (assoc req2 :session {:user "alice"})
      {:keys [uri request-method headers params session body]} req3]
  (if (and (= request-method :post)
           (= (:user session) "alice")
           (= (:query params) "search")
           (= (:content-type headers) "application/json"))
    body
    nil))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 30.5M | 8.47M | 0.28x |
| **p50 latency (ns)** | 42 | 125 | 2.98x |
| **p95 latency (ns)** | 83 | 167 | 2.01x |
| **Allocation (B/op)** | 184 | 832 | 4.52x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### cond-option-pipeline

```clojure
(let [raw-timeout "500"
      opts (-> {}
               (cond-> true (assoc :id "btn"))
               (cond-> true (assoc :role "primary"))
               (cond-> true (assoc :href "/submit"))
               (cond-> raw-timeout (assoc :timeout raw-timeout)))
      {:keys [id role href timeout]} opts]
  (if (and (= id "btn")
           (= role "primary")
           (= href "/submit"))
    timeout
    nil))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 42.9M | 6.48M | 0.15x |
| **p50 latency (ns)** | 41 | 167 | 4.07x |
| **p95 latency (ns)** | 42 | 209 | 4.98x |
| **Allocation (B/op)** | 208 | 896 | 4.31x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### event-enrich

```clojure
(let [event {:id "evt-101" :type :auth :user "alice" :tenant "org-1"
             :ip "127.0.0.1" :status :ok :timestamp "2026-09-06" :version :v1}
      enriched (assoc event :payload "ok")
      {:keys [id status user payload]} enriched]
  (if (and (= id "evt-101")
           (= status :ok)
           (= user "alice"))
    payload
    nil))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 8.06M | 13.5M | 1.67x |
| **p50 latency (ns)** | 125 | 83 | 0.66x |
| **p95 latency (ns)** | 167 | 125 | 0.75x |
| **Allocation (B/op)** | 416 | 384 | 0.92x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### event-sanitize

```clojure
(let [event {:id "evt-101" :user "alice" :secret "secret-token" :temp "temp-999" :status :ok}
      sanitized (-> event (dissoc :secret) (dissoc :temp))
      {:keys [id user secret temp status]} sanitized]
  (if (and (= id "evt-101")
           (= status :ok)
           (= user "alice")
           (nil? secret)
           (nil? temp))
    id
    nil))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 53.1M | 13.6M | 0.26x |
| **p50 latency (ns)** | 41 | 84 | 2.05x |
| **p95 latency (ns)** | 42 | 125 | 2.98x |
| **Allocation (B/op)** | 152 | 480 | 3.16x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### fixed-str2

```clojure
(str :api/route 'handler/name)
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 80.1M | 160M | 2.00x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 168 | 64 | 0.38x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._


### Metric Definitions

- **Throughput (ops/sec)**: Sustained execution rate (higher is better).
- **p50 / p95 latency**: 50th and 95th percentile invocation response times (lower is better). JMH sample mode often hits a timer resolution floor (~40 ns on this host), so near-floor values measure the clock more than the snippet.
- **Allocation (B/op) / GC pressure**: Heap bytes allocated per operation (`gc.alloc.rate.norm`). Lower values indicate less GC pressure; near-zero often means Truffle Partial Escape Analysis / scalar replacement.
