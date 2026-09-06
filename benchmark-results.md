# Clojure vs Cloffle Performance Comparison

**Date:** 2026-09-06  
**Environment:** Mac OS X (aarch64), Java 25.0.4.1  

Built-in guest samples, compared via direct `IFn.invoke`.

### Summary

| Sample | Clojure (ops/sec) | Cloffle (ops/sec) | Speedup (x) | Clojure p95 (ns) | Cloffle p95 (ns) | Clojure alloc (B/op) | Cloffle alloc (B/op) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `consume-assoc` | 163M | 238M | 1.46 | 42 | 42 | 72 | 0 |
| `array-map-lookup` | 407M | 235M | 0.58 | 42 | 42 | 0 | 0 |
| `keyword-invoke` | 353M | 249M | 0.70 | 42 | 42 | 0 | 0 |
| `nested-get-in` | 51.7M | 238M | 4.60 | 42 | 42 | 64 | 0 |
| `assoc-pipeline` | 191M | 232M | 1.21 | 42 | 42 | 80 | 0 |
| `ephemeral-pipeline` | 179M | 237M | 1.32 | 42 | 42 | 72 | 0 |
| `ephemeral-insert` | 127M | 237M | 1.87 | 42 | 42 | 72 | 0 |
| `ephemeral-promote8` | 5.13M | 261M | 50.89 | 250 | 42 | 648 | 0 |
| `ephemeral-dissoc` | 107M | 253M | 2.37 | 42 | 42 | 64 | 0 |
| `tuple-destructure` | 465M | 236M | 0.51 | 42 | 42 | 0 | 0 |
| `lazy-seq-first` | 71.9M | 260M | 3.62 | 42 | 42 | 136 | 0 |
| `lazy-seq-vec-first` | 69.3M | 233M | 3.36 | 42 | 42 | 136 | 0 |
| `tuple2-transform` | 480M | 260M | 0.54 | 42 | 42 | 0 | 0 |
| `ring-response` | 32.5M | 246M | 7.56 | 42 | 42 | 208 | 0 |
| `hiccup-normalize` | 287M | 252M | 0.88 | 42 | 42 | 0 | 0 |
| `kwargs-destructure` | 347M | 263M | 0.76 | 42 | 42 | 0 | 0 |
| `middleware-pipeline` | 30.0M | 260M | 8.69 | 83 | 42 | 184 | 0 |
| `cond-option-pipeline` | 44.9M | 238M | 5.30 | 42 | 42 | 208 | 0 |
| `event-enrich` | 7.88M | 240M | 30.44 | 167 | 42 | 416 | 0 |
| `event-sanitize` | 55.4M | 234M | 4.23 | 42 | 42 | 152 | 0 |
| `fixed-str2` | 79.3M | 156M | 1.97 | 42 | 42 | 168 | 64 |
| `fixed-str3` | 60.1M | 101M | 1.68 | 42 | 42 | 176 | 72 |

_Speedup (x) is Cloffle ÷ Clojure throughput. Latency columns share one unit chosen from the largest p95 across all samples._

### consume-assoc

```clojure
(let [m {:a :v1, :b :v2, :c :v3}] (:a (assoc m :b :v999)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 163M | 238M | 1.46x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### array-map-lookup

```clojure
(get {:a :v1 :b :v2 :c :v3} :b)
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 407M | 235M | 0.58x |
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
| **Throughput (ops/sec)** | 353M | 249M | 0.70x |
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
| **Throughput (ops/sec)** | 51.7M | 238M | 4.60x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 64 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### assoc-pipeline

```clojure
(get (assoc {:a :v1 :b :v2 :c :v3} :status :active) :status)
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 191M | 232M | 1.21x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 80 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### ephemeral-pipeline

```clojure
(let [m {:a "initial" :b :v2 :c :v3}]
  (:a (assoc m :a "replacement")))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 179M | 237M | 1.32x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 127M | 237M | 1.87x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 5.13M | 261M | 50.89x |
| **p50 latency (ns)** | 208 | 0 | 0.00x |
| **p95 latency (ns)** | 250 | 42 | 0.17x |
| **Allocation (B/op)** | 648 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 107M | 253M | 2.37x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 64 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 465M | 236M | 0.51x |
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
| **Throughput (ops/sec)** | 71.9M | 260M | 3.62x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 136 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### lazy-seq-vec-first

```clojure
(first (lazy-seq [:first]))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 69.3M | 233M | 3.36x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 136 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### tuple2-transform

```clojure
(let [[a b] [:first :second]
      [c d] [b a]]
  c)
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 480M | 260M | 0.54x |
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
| **Throughput (ops/sec)** | 32.5M | 246M | 7.56x |
| **p50 latency (ns)** | 42 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 208 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 287M | 252M | 0.88x |
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
| **Throughput (ops/sec)** | 347M | 263M | 0.76x |
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
| **Throughput (ops/sec)** | 30.0M | 260M | 8.69x |
| **p50 latency (ns)** | 42 | 0 | 0.00x |
| **p95 latency (ns)** | 83 | 42 | 0.51x |
| **Allocation (B/op)** | 184 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 44.9M | 238M | 5.30x |
| **p50 latency (ns)** | 42 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 208 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 7.88M | 240M | 30.44x |
| **p50 latency (ns)** | 125 | 0 | 0.00x |
| **p95 latency (ns)** | 167 | 42 | 0.25x |
| **Allocation (B/op)** | 416 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 55.4M | 234M | 4.23x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 152 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### fixed-str2

```clojure
(str :api/route 'handler/name)
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 79.3M | 156M | 1.97x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 168 | 64 | 0.38x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### fixed-str3

```clojure
(str \x "42" true)
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 60.1M | 101M | 1.68x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 176 | 72 | 0.41x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### Metric Definitions

- **Throughput (ops/sec)**: Sustained execution rate (higher is better).
- **p50 / p95 latency**: 50th and 95th percentile invocation response times (lower is better). JMH sample mode often hits a timer resolution floor (~40 ns on this host), so near-floor values measure the clock more than the snippet.
- **Allocation (B/op) / GC pressure**: Heap bytes allocated per operation (`gc.alloc.rate.norm`). Lower values indicate less GC pressure; near-zero often means Truffle Partial Escape Analysis / scalar replacement.
