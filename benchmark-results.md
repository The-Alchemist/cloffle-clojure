# Clojure vs Cloffle Performance Comparison

**Date:** 2026-09-10  
**Environment:** Mac OS X (aarch64), Java 25.0.4.1  

Built-in guest samples, compared via direct `IFn.invoke`.

### Summary

| Sample | Clojure (ops/sec) | Cloffle (ops/sec) | Speedup (x) | Clojure p95 (µs) | Cloffle p95 (µs) | Clojure alloc (B/op) | Cloffle alloc (B/op) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `consume-assoc` | 166M | 234M | 1.41 | 0.04 | 0.04 | 72 | 0 |
| `consume-assoc-no-let` | 177M | 248M | 1.41 | 0.04 | 0.04 | 72 | 0 |
| `assoc-only` | 198M | 195M | 0.98 | 0.04 | 0.04 | 72 | 64 |
| `assoc-return-nil` | 155M | 155M | 1.00 | 0.04 | 0.04 | 72 | 0 |
| `array-map-lookup` | 453M | 230M | 0.51 | 0.04 | 0.04 | 0 | 0 |
| `hash-map-lookup` | 306M | 137M | 0.45 | 0.04 | 0.04 | 0 | 0 |
| `shape-map16-lookup` | 396M | 236M | 0.60 | 0.04 | 0.04 | 0 | 0 |
| `rt-get-lookup` | 410M | 236M | 0.58 | 0.04 | 0.04 | 0 | 0 |
| `keyword-invoke` | 327M | 262M | 0.80 | 0.04 | 0.04 | 0 | 0 |
| `nested-get-in` | 55.2M | 158M | 2.86 | 0.04 | 0.04 | 64 | 0 |
| `assoc-pipeline` | 186M | 236M | 1.27 | 0.04 | 0.04 | 80 | 0 |
| `ephemeral-pipeline` | 170M | 237M | 1.39 | 0.04 | 0.04 | 72 | 0 |
| `ephemeral-insert` | 142M | 185M | 1.31 | 0.04 | 0.04 | 72 | 0 |
| `ephemeral-promote8` | 5.38M | 199M | 36.99 | 0.29 | 0.04 | 608 | 0 |
| `ephemeral-dissoc` | 109M | 203M | 1.86 | 0.04 | 0.04 | 64 | 0 |
| `consume-conj-vector` | 423M | 181M | 0.43 | 0.04 | 0.08 | 0 | 0 |
| `consume-conj-map` | 76.2M | 34.8M | 0.46 | 0.04 | 0.00 | 104 | 304 |
| `consume-conj-list` | 157M | 68.9M | 0.44 | 0.04 | 0.04 | 120 | 128 |
| `conj-chain` | 331M | 17.7M | 0.05 | 0.04 | 0.08 | 0 | 584 |
| `tuple-destructure` | 470M | 175M | 0.37 | 0.04 | 0.04 | 0 | 0 |
| `lazy-seq-first` | 70.3M | 44.6M | 0.63 | 0.04 | 0.04 | 136 | 168 |
| `lazy-seq-vec-first` | 70.4M | 44.6M | 0.63 | 0.04 | 0.04 | 136 | 168 |
| `tuple2-transform` | 495M | 56.6M | 0.11 | 0.04 | 0.04 | 0 | 0 |
| `ring-response` | 33.9M | 179M | 5.29 | 0.04 | 0.04 | 208 | 0 |
| `hiccup-normalize` | 285M | 15.5M | 0.05 | 0.04 | 0.13 | 0 | 288 |
| `kwargs-destructure` | 319M | 201M | 0.63 | 0.04 | 0.04 | 0 | 0 |
| `middleware-pipeline` | 30.8M | 175M | 5.69 | 0.08 | 0.04 | 184 | 0 |
| `cond-option-pipeline` | 45.3M | 183M | 4.05 | 0.04 | 0.04 | 208 | 0 |
| `event-enrich` | 7.54M | 183M | 24.21 | 0.21 | 0.04 | 416 | 0 |
| `event-sanitize` | 52.7M | 184M | 3.49 | 0.04 | 0.04 | 152 | 0 |
| `fixed-str2` | 69.7M | 1.11M | 0.02 | 0.04 | 1.12 | 168 | 5,088.1 |
| `cross-call-map` | 124M | 152M | 1.23 | 0.04 | 0.00 | 56 | 0 |
| `cond-shape-poly` | 49.1M | 199M | 4.06 | 0.04 | 0.00 | 160 | 0 |
| `prim-literal-add` | 505M | 74.3M | 0.15 | 0.04 | 0.00 | 0 | 48 |
| `prim-hinted-locals` | 498M | 38.2M | 0.08 | 0.04 | 0.00 | 0 | 96 |
| `prim-long-loop` | 505M | 645K | 0.00 | 0.04 | 0.00 | 0 | 7,248.1 |
| `prim-double-loop` | 22.4M | 501K | 0.02 | 0.08 | 0.00 | 24 | 9,672.1 |
| `prim-java-int` | 503M | 259M | 0.52 | 0.04 | 0.04 | 0 | 0 |
| `prim-object-boundary` | 494M | 16.1M | 0.03 | 0.04 | 0.08 | 0 | 560 |

_Speedup (x) is Cloffle ÷ Clojure throughput. Latency columns share one unit chosen from the largest p95 across all samples._

### consume-assoc

```clojure
(let [m {:a :v1, :b :v2, :c :v3}] (:a (assoc m :b :v999)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 166M | 234M | 1.41x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### consume-assoc-no-let

```clojure
(:a (assoc {:a :v1, :b :v2, :c :v3} :b :v999))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 177M | 248M | 1.41x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### assoc-only

```clojure
(let [m {:a :v1, :b :v2, :c :v3}]
  (assoc m :b :v999))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 198M | 195M | 0.98x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 64 | 0.89x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### assoc-return-nil

```clojure
(let [m2 (assoc {:a :v1, :b :v2, :c :v3} :b :v999)]
  (when (= (:b m2) :v999)
    nil))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 155M | 155M | 1.00x |
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
| **Throughput (ops/sec)** | 453M | 230M | 0.51x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### hash-map-lookup

```clojure
(get {:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8 :k9 :v9 :k10 :v10 :k11 :v11 :k12 :v12 :k13 :v13 :k14 :v14 :k15 :v15 :k16 :v16 :k17 :v17} :k5)
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 306M | 137M | 0.45x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### shape-map16-lookup

```clojure
(get {:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8 :k9 :v9 :k10 :v10 :k11 :v11} :k6)
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 396M | 236M | 0.60x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### rt-get-lookup

```clojure
(clojure.lang.RT/get {:a :v1 :b :v2 :c :v3} :b)
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 410M | 236M | 0.58x |
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
| **Throughput (ops/sec)** | 327M | 262M | 0.80x |
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
| **Throughput (ops/sec)** | 55.2M | 158M | 2.86x |
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
| **Throughput (ops/sec)** | 186M | 236M | 1.27x |
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
| **Throughput (ops/sec)** | 170M | 237M | 1.39x |
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
| **Throughput (ops/sec)** | 142M | 185M | 1.31x |
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
| **Throughput (ops/sec)** | 5.38M | 199M | 36.99x |
| **p50 latency (ns)** | 208 | 0 | 0.00x |
| **p95 latency (ns)** | 292 | 42 | 0.14x |
| **Allocation (B/op)** | 608 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 109M | 203M | 1.86x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 64 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### consume-conj-vector

```clojure
(let [v [:v1 :v2]] (peek (conj v :v3)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 423M | 181M | 0.43x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 84 | 2.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### consume-conj-map

```clojure
(let [m {:a :v1 :b :v2}] (:c (conj m {:c :v3})))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 76.2M | 34.8M | 0.46x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 0 | 0.00x |
| **Allocation (B/op)** | 104 | 304 | 2.92x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### consume-conj-list

```clojure
(let [l (list :v2 :v3)] (first (conj l :v1)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 157M | 68.9M | 0.44x |
| **p50 latency (ns)** | 0 | 41 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 120 | 128 | 1.07x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### conj-chain

```clojure
(let [v (conj (conj (conj [] :v1) :v2) :v3)]
  (if (= (peek v) :v3)
    (first v)
    nil))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 331M | 17.7M | 0.05x |
| **p50 latency (ns)** | 0 | 83 | - |
| **p95 latency (ns)** | 42 | 84 | 2.00x |
| **Allocation (B/op)** | 0 | 584 | - |

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
| **Throughput (ops/sec)** | 470M | 175M | 0.37x |
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
| **Throughput (ops/sec)** | 70.3M | 44.6M | 0.63x |
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
| **Throughput (ops/sec)** | 70.4M | 44.6M | 0.63x |
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
| **Throughput (ops/sec)** | 495M | 56.6M | 0.11x |
| **p50 latency (ns)** | 0 | 41 | - |
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
| **Throughput (ops/sec)** | 33.9M | 179M | 5.29x |
| **p50 latency (ns)** | 42 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 208 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### hiccup-normalize

```clojure
;; PROVISIONAL: `nth` puts a boxed Long index on the measured path, so this snippet's number is
;; dominated by index boxing rather than by the lowering layer. Revisit after primitives are
;; specialized; until then treat it as a workload sample, not a benchmark.
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
| **Throughput (ops/sec)** | 285M | 15.5M | 0.05x |
| **p50 latency (ns)** | 0 | 83 | - |
| **p95 latency (ns)** | 42 | 125 | 2.98x |
| **Allocation (B/op)** | 0 | 288 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### kwargs-destructure

```clojure
(let [opts {:method :post :timeout "500ms"}
      {:keys [method timeout] :or {method :get timeout "1000ms"}} opts]
  (if (= method :post) timeout "none"))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 319M | 201M | 0.63x |
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
| **Throughput (ops/sec)** | 30.8M | 175M | 5.69x |
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
| **Throughput (ops/sec)** | 45.3M | 183M | 4.05x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
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
| **Throughput (ops/sec)** | 7.54M | 183M | 24.21x |
| **p50 latency (ns)** | 166 | 0 | 0.00x |
| **p95 latency (ns)** | 208 | 42 | 0.20x |
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
| **Throughput (ops/sec)** | 52.7M | 184M | 3.49x |
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
| **Throughput (ops/sec)** | 69.7M | 1.11M | 0.02x |
| **p50 latency (µs)** | 0.04 | 0.92 | 22.37x |
| **p95 latency (µs)** | 0.04 | 1.12 | 26.76x |
| **Allocation (B/op)** | 168 | 5,088.1 | 30.29x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### cross-call-map

```clojure
((fn [s h b]
   (let [resp {:status s :headers h :body b}
         {:keys [status headers body]} resp]
     (if (and (= status :ok)
              (= (:content-type headers) "text/plain"))
       body
       nil)))
 :ok {:content-type "text/plain"} "hello")
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 124M | 152M | 1.23x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 0 | 0.00x |
| **Allocation (B/op)** | 56 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### cond-shape-poly

```clojure
(let [a true b true c false d true
      opts (-> {}
               (cond-> a (assoc :alpha :va))
               (cond-> b (assoc :beta :vb))
               (cond-> c (assoc :gamma :vg))
               (cond-> d (assoc :delta :vd)))
      v (get opts :alpha :none)]
  (if (and (= v :va)
           (= (get opts :beta :none) :vb)
           (= (get opts :gamma :none) :none)
           (= (get opts :delta :none) :vd))
    v
    nil))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 49.1M | 199M | 4.06x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 0 | 0.00x |
| **Allocation (B/op)** | 160 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### prim-literal-add

```clojure
;; Hot long+long through Numbers; Object boundary must still hand back Long.
(clojure.lang.Numbers/add 1 2)
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 505M | 74.3M | 0.15x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 0 | 0.00x |
| **Allocation (B/op)** | 0 | 48 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### prim-hinted-locals

```clojure
;; Hinted long locals with checked arithmetic.
((fn* [^long a ^long b]
   (clojure.lang.Numbers/add a (clojure.lang.Numbers/multiply b 3)))
 7 5)
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 498M | 38.2M | 0.08x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 0 | 0.00x |
| **Allocation (B/op)** | 0 | 96 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### prim-long-loop

```clojure
;; Tight long loop/recur — allocation should be dominated by the final Long box, not per-iter boxing.
(loop* [i 0]
  (if (clojure.lang.Numbers/lt i 100)
    (recur (clojure.lang.Numbers/unchecked_inc i))
    i))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 505M | 645K | 0.00x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 0 | 0.00x |
| **Allocation (B/op)** | 0 | 7,248.1 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### prim-double-loop

```clojure
(loop* [i 0.0]
  (if (clojure.lang.Numbers/lt i 100.0)
    (recur (clojure.lang.Numbers/add i 1.0))
    i))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 22.4M | 501K | 0.02x |
| **p50 latency (ns)** | 42 | 0 | 0.00x |
| **p95 latency (ns)** | 84 | 0 | 0.00x |
| **Allocation (B/op)** | 24 | 9,672.1 | 403.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### prim-java-int

```clojure
;; Host int return must remain Integer at the Object boundary.
(Integer/parseInt "42")
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 503M | 259M | 0.52x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### prim-object-boundary

```clojure
;; Forces a Long box into a collection then reads it back.
(clojure.lang.RT/nth
  (clojure.lang.RT/conj clojure.lang.PersistentVector/EMPTY
                        (clojure.lang.Numbers/add 10 20))
  0)
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 494M | 16.1M | 0.03x |
| **p50 latency (ns)** | 0 | 83 | - |
| **p95 latency (ns)** | 42 | 84 | 2.00x |
| **Allocation (B/op)** | 0 | 560 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### Metric Definitions

- **Throughput (ops/sec)**: Sustained execution rate (higher is better).
- **p50 / p95 latency**: 50th and 95th percentile invocation response times (lower is better). JMH sample mode often hits a timer resolution floor (~40 ns on this host), so near-floor values measure the clock more than the snippet.
- **Allocation (B/op) / GC pressure**: Heap bytes allocated per operation (`gc.alloc.rate.norm`). Lower values indicate less GC pressure; near-zero often means Truffle Partial Escape Analysis / scalar replacement.
