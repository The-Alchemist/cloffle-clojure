# Clojure vs Cloffle Performance Comparison

**Date:** 2026-09-15  
**Environment:** Mac OS X (aarch64), Java 25.0.4.1  

Built-in guest samples, compared via direct `IFn.invoke`.

### Summary

| Sample | Clojure (ops/sec) | Cloffle (ops/sec) | Speedup (x) | Clojure p95 (ns) | Cloffle p95 (ns) | Clojure alloc (B/op) | Cloffle alloc (B/op) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `consume-assoc` | 147M | 85.6M | 0.58 | 42 | 42 | 72 | 128 |
| `consume-assoc-no-let` | 155M | 69.6M | 0.45 | 42 | 42 | 72 | 128 |
| `assoc-only` | 191M | 89.5M | 0.47 | 42 | 42 | 72 | 128 |
| `assoc-return-nil` | 139M | 66.9M | 0.48 | 42 | 42 | 72 | 128 |
| `array-map-lookup` | 312M | 169M | 0.54 | 42 | 42 | 0 | 0 |
| `hash-map-lookup` | 238M | 104M | 0.44 | 42 | 42 | 0 | 0 |
| `shape-map16-lookup` | 277M | 173M | 0.63 | 42 | 42 | 0 | 0 |
| `rt-get-lookup` | 333M | 236M | 0.71 | 42 | 42 | 0 | 0 |
| `keyword-invoke` | 254M | 263M | 1.04 | 42 | 42 | 0 | 0 |
| `nested-get-in` | 51.4M | 168M | 3.27 | 42 | 42 | 64 | 0 |
| `assoc-pipeline` | 164M | 47.3M | 0.29 | 42 | 42 | 80 | 176 |
| `ephemeral-pipeline` | 149M | 71.8M | 0.48 | 42 | 42 | 72 | 128 |
| `ephemeral-insert` | 126M | 38.2M | 0.30 | 42 | 42 | 72 | 176 |
| `ephemeral-promote8` | 5.11M | 42.6M | 8.33 | 292 | 42 | 648 | 240 |
| `ephemeral-dissoc` | 103M | 54.1M | 0.52 | 42 | 42 | 64 | 176 |
| `consume-conj-vector` | 293M | 178M | 0.61 | 42 | 42 | 0 | 0 |
| `consume-conj-map` | 71.0M | 32.9M | 0.46 | 42 | 83 | 104 | 304 |
| `consume-conj-list` | 158M | 178M | 1.13 | 42 | 42 | 120 | 0 |
| `conj-chain` | 231M | 175M | 0.76 | 42 | 42 | 0 | 0 |
| `tuple-destructure` | 349M | 201M | 0.57 | 42 | 42 | 0 | 0 |
| `lazy-seq-first` | 68.4M | 46.2M | 0.68 | 42 | 42 | 136 | 104 |
| `lazy-seq-vec-first` | 66.7M | 49.7M | 0.74 | 42 | 42 | 136 | 104 |
| `tuple2-transform` | 346M | 237M | 0.69 | 42 | 42 | 0 | 0 |
| `into-empty-tuple2` | 57.3M | 93.9M | 1.64 | 42 | 42 | 328 | 64 |
| `into-empty-tuple2-dynamic` | 56.6M | 192M | 3.40 | 42 | 42 | 328 | 0 |
| `into-map-small` | 7.28M | 1.51M | 0.21 | 208 | 750 | 832 | 9,576.1 |
| `map-first-status` | 22.7M | 1.54M | 0.07 | 83 | 709 | 376 | 9,520.1 |
| `map-small-records` | 20.9M | 1.51M | 0.07 | 84 | 750 | 368 | 9,728.1 |
| `into-map-ids` | 7.24M | 1.52M | 0.21 | 167 | 750 | 832 | 9,776.1 |
| `into-map-ids-dynamic` | 8.29M | 1.52M | 0.18 | 167 | 875 | 808 | 9,408.1 |
| `map-first-status-list` | 29.9M | 1.59M | 0.05 | 84 | 709 | 352 | 9,520.1 |
| `map-first-status-seq` | 43.5M | 2.68M | 0.06 | 42 | 417 | 240 | 8,768 |
| `map-first-status-dynamic` | 15.9M | 1.53M | 0.10 | 84 | 709 | 768 | 9,360.1 |
| `map-filter-status-dynamic` | 11.6M | 2.15M | 0.19 | 125 | 542 | 1,080 | 10,696 |
| `map-filter-status-transduce` | 17.8M | 1.69M | 0.10 | 84 | 667 | 736 | 10,024.1 |
| `row-first-field-dynamic` | 44.7M | 177M | 3.96 | 42 | 42 | 432 | 0 |
| `rows-count-dynamic` | 61.0M | 128M | 2.10 | 42 | 42 | 328 | 0 |
| `map-field-rows` | 18.2M | 1.53M | 0.08 | 84 | 709 | 776 | 9,360.1 |
| `map-field-rows-runtime` | 17.7M | 1.51M | 0.09 | 84 | 709 | 824 | 9,432.1 |
| `map-field-rows-nth` | 17.1M | 1.57M | 0.09 | 125 | 667 | 776 | 9,360.1 |
| `map-field-rows-seq` | 17.2M | 2.63M | 0.15 | 84 | 458 | 768 | 8,768 |
| `filter-rows-dynamic` | 15.4M | 10.4M | 0.68 | 84 | 125 | 792 | 1,976 |
| `filter-rows-count-dynamic` | 14.0M | 5.14M | 0.37 | 125 | 250 | 760 | 2,968 |
| `filter-after-map-id-dynamic` | 12.5M | 1.35M | 0.11 | 125 | 833 | 1,048 | 11,328.1 |
| `filter-after-map-identity-dynamic` | 10.3M | 1.33M | 0.13 | 125 | 833 | 1,072 | 11,352.1 |
| `map-small-vector` | 20.3M | 1.43M | 0.07 | 84 | 750 | 368 | 9,528.1 |
| `map-first-small` | 18.4M | 1.56M | 0.08 | 83 | 875 | 392 | 9,432.1 |
| `map-first-one` | 32.6M | 1.52M | 0.05 | 84 | 833 | 352 | 9,416.1 |
| `map-identity-vector` | 15.9M | 1.57M | 0.10 | 125 | 750 | 472 | 9,432.1 |
| `mapv-small-vector` | 36.4M | 3.07M | 0.08 | 42 | 375 | 328 | 3,064 |
| `ladder-nth5-keywords` | 329M | 172M | 0.52 | 42 | 42 | 0 | 0 |
| `ladder-first5-keywords` | 297M | 179M | 0.60 | 42 | 42 | 0 | 0 |
| `ladder-seq-first5-keywords` | 270M | 176M | 0.65 | 42 | 42 | 0 | 0 |
| `ring-response` | 32.9M | 18.6M | 0.57 | 42 | 84 | 208 | 368 |
| `hiccup-normalize` | 215M | 177M | 0.82 | 42 | 42 | 0 | 0 |
| `hiccup-normalize-small` | 32.6M | 34.6M | 1.06 | 42 | 42 | 256 | 232 |
| `identical-nil-dynamic` | 264M | 13.7M | 0.05 | 42 | 125 | 0 | 216 |
| `norm-tuple-nth` | 211M | 192M | 0.91 | 42 | 42 | 0 | 0 |
| `kwargs-destructure` | 260M | 164M | 0.63 | 42 | 42 | 0 | 0 |
| `middleware-pipeline` | 29.3M | 12.6M | 0.43 | 83 | 125 | 184 | 480 |
| `cond-option-pipeline` | 43.1M | 13.5M | 0.31 | 42 | 125 | 208 | 448 |
| `event-enrich` | 7.33M | 30.4M | 4.15 | 167 | 83 | 376 | 240 |
| `event-sanitize` | 53.2M | 21.3M | 0.40 | 42 | 84 | 152 | 288 |
| `fixed-str2` | 74.4M | 7.35M | 0.10 | 42 | 208 | 168 | 864 |
| `cross-call-map` | 132M | 66.2M | 0.50 | 42 | 42 | 40 | 128 |
| `cross-call-nested-maps` | 42.3M | 22.8M | 0.54 | 42 | 84 | 136 | 304 |
| `cross-call-nested-large` | 4.78M | 3.39M | 0.71 | 292 | 333 | 1,080 | 1,400 |
| `cross-call-nested-deep` | 13.4M | 13.4M | 1.00 | 125 | 125 | 320 | 816 |
| `cross-call-nested-rows` | 15.6M | 19.4M | 1.24 | 125 | 84 | 280 | 744 |
| `cross-call-jsonapi` | 14.0M | 12.5M | 0.89 | 125 | 125 | 360 | 816 |
| `cross-call-defn-pipeline` | 18.2M | 7.91M | 0.43 | 84 | 167 | 344 | 656 |
| `cross-call-validation-pipeline` | 5.57M | 5.62M | 1.01 | 209 | 250 | 792 | 1,192 |
| `cross-call-validation-pipeline-threaded` | 5.10M | 5.59M | 1.10 | 250 | 250 | 792 | 1,192 |
| `cond-shape-poly` | 58.1M | 17.8M | 0.31 | 42 | 84 | 160 | 336 |
| `prim-literal-add` | 354M | 246M | 0.70 | 42 | 42 | 0 | 0 |
| `prim-hinted-locals` | 320M | 141M | 0.44 | 42 | 42 | 0 | 0 |
| `prim-long-loop` | 348M | 23.5M | 0.07 | 42 | 84 | 0 | 0 |
| `prim-double-loop` | 24.3M | 16.6M | 0.68 | 83 | 84 | 24 | 48 |
| `prim-count` | 354M | 235M | 0.66 | 42 | 42 | 0 | 0 |
| `prim-nth` | 358M | 247M | 0.69 | 42 | 42 | 0 | 0 |
| `prim-java-int` | 353M | 255M | 0.72 | 42 | 42 | 0 | 0 |
| `prim-object-boundary` | 360M | 223M | 0.62 | 42 | 42 | 0 | 0 |

_Speedup (x) is Cloffle ÷ Clojure throughput. Latency columns share one unit chosen from the largest p95 across all samples._

### consume-assoc

```clojure
(ns bench.snippet.consume-assoc)

(defn bench []
    (let [m {:a :v1, :b :v2, :c :v3}] (:a (assoc m :b :v999))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 147M | 85.6M | 0.58x |
| **p50 latency (ns)** | 0 | 41 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 128 | 1.78x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### consume-assoc-no-let

```clojure
(ns bench.snippet.consume-assoc-no-let)

(defn bench []
    (:a (assoc {:a :v1, :b :v2, :c :v3} :b :v999)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 155M | 69.6M | 0.45x |
| **p50 latency (ns)** | 0 | 41 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 128 | 1.78x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### assoc-only

```clojure
(ns bench.snippet.assoc-only)

(defn bench []
    (let [m {:a :v1, :b :v2, :c :v3}]
    (assoc m :b :v999)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 191M | 89.5M | 0.47x |
| **p50 latency (ns)** | 0 | 41 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 128 | 1.78x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### assoc-return-nil

```clojure
(ns bench.snippet.assoc-return-nil)

(defn bench []
    (let [m2 (assoc {:a :v1, :b :v2, :c :v3} :b :v999)]
    (when (= (:b m2) :v999)
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 139M | 66.9M | 0.48x |
| **p50 latency (ns)** | 0 | 41 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 128 | 1.78x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### array-map-lookup

```clojure
(ns bench.snippet.array-map-lookup)

(defn bench []
    (get {:a :v1 :b :v2 :c :v3} :b))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 312M | 169M | 0.54x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### hash-map-lookup

```clojure
(ns bench.snippet.hash-map-lookup)

(defn bench []
    (get {:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8 :k9 :v9 :k10 :v10 :k11 :v11 :k12 :v12 :k13 :v13 :k14 :v14 :k15 :v15 :k16 :v16 :k17 :v17} :k5))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 238M | 104M | 0.44x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### shape-map16-lookup

```clojure
(ns bench.snippet.shape-map16-lookup)

(defn bench []
    (get {:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8 :k9 :v9 :k10 :v10 :k11 :v11} :k6))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 277M | 173M | 0.63x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### rt-get-lookup

```clojure
(ns bench.snippet.rt-get-lookup)

(defn bench []
    (clojure.lang.RT/get {:a :v1 :b :v2 :c :v3} :b))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 333M | 236M | 0.71x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### keyword-invoke

```clojure
(ns bench.snippet.keyword-invoke)

(defn bench []
    (:b {:a :v1 :b :v2 :c :v3}))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 254M | 263M | 1.04x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### nested-get-in

```clojure
(ns bench.snippet.nested-get-in)

(defn bench []
    (get-in {:user {:profile {:name "Alice"}}} [:user :profile :name]))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 51.4M | 168M | 3.27x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 64 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### assoc-pipeline

```clojure
(ns bench.snippet.assoc-pipeline)

(defn bench []
    (get (assoc {:a :v1 :b :v2 :c :v3} :status :active) :status))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 164M | 47.3M | 0.29x |
| **p50 latency (ns)** | 0 | 41 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 80 | 176 | 2.20x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### ephemeral-pipeline

```clojure
(ns bench.snippet.ephemeral-pipeline)

(defn bench []
    (let [m {:a "initial" :b :v2 :c :v3}]
    (:a (assoc m :a "replacement"))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 149M | 71.8M | 0.48x |
| **p50 latency (ns)** | 0 | 41 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 128 | 1.78x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### ephemeral-insert

```clojure
(ns bench.snippet.ephemeral-insert)

(defn bench []
    (let [m {:a :v1 :b :v2}
        m2 (assoc m :c :v3)]
    (if (= (:a m2) :v1)
      (:c m2)
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 126M | 38.2M | 0.30x |
| **p50 latency (ns)** | 0 | 42 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 176 | 2.44x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### ephemeral-promote8

```clojure
(ns bench.snippet.ephemeral-promote8)

(defn bench []
    (let [m {:p0 :v0 :p1 :v1 :p2 :v2 :p3 :v3 :p4 :v4 :p5 :v5 :p6 :v6 :p7 :v7}
        m2 (assoc m :p8 :v8)]
    (if (= (:p0 m2) :v0)
      (:p8 m2)
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 5.11M | 42.6M | 8.33x |
| **p50 latency (ns)** | 208 | 42 | 0.20x |
| **p95 latency (ns)** | 292 | 42 | 0.14x |
| **Allocation (B/op)** | 648 | 240 | 0.37x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### ephemeral-dissoc

```clojure
(ns bench.snippet.ephemeral-dissoc)

(defn bench []
    (let [m {:a :v1 :b :v3 :c :v3}
        m2 (dissoc m :b)]
    (if (= (:a m2) :v1)
      (:c m2)
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 103M | 54.1M | 0.52x |
| **p50 latency (ns)** | 0 | 41 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 64 | 176 | 2.75x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### consume-conj-vector

```clojure
(ns bench.snippet.consume-conj-vector)

(defn bench []
    (let [v [:v1 :v2]] (peek (conj v :v3))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 293M | 178M | 0.61x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### consume-conj-map

```clojure
(ns bench.snippet.consume-conj-map)

(defn bench []
    (let [m {:a :v1 :b :v2}] (:c (conj m {:c :v3}))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 71.0M | 32.9M | 0.46x |
| **p50 latency (ns)** | 41 | 42 | 1.02x |
| **p95 latency (ns)** | 42 | 83 | 1.98x |
| **Allocation (B/op)** | 104 | 304 | 2.92x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### consume-conj-list

```clojure
(ns bench.snippet.consume-conj-list)

(defn bench []
    (let [l (list :v2 :v3)] (first (conj l :v1))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 158M | 178M | 1.13x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 120 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### conj-chain

```clojure
(ns bench.snippet.conj-chain)

(defn bench []
    (let [v (conj (conj (conj [] :v1) :v2) :v3)]
    (if (= (peek v) :v3)
      (first v)
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 231M | 175M | 0.76x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### tuple-destructure

```clojure
(ns bench.snippet.tuple-destructure)

(defn bench []
    (let [[a b] [:first :second]]
    (if (= a :first)
      b
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 349M | 201M | 0.57x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### lazy-seq-first

```clojure
(ns bench.snippet.lazy-seq-first)

(defn bench []
    (first (lazy-seq [:first])))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 68.4M | 46.2M | 0.68x |
| **p50 latency (ns)** | 41 | 41 | 1.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 136 | 104 | 0.76x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### lazy-seq-vec-first

```clojure
(ns bench.snippet.lazy-seq-vec-first)

(defn bench []
    (first (lazy-seq [:first])))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 66.7M | 49.7M | 0.74x |
| **p50 latency (ns)** | 41 | 41 | 1.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 136 | 104 | 0.76x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### tuple2-transform

```clojure
(ns bench.snippet.tuple2-transform)

(defn bench []
    (let [[a b] [:first :second]
        [c d] [b a]]
    c))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 346M | 237M | 0.69x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### into-empty-tuple2

```clojure
(ns bench.snippet.into-empty-tuple2)

(defn bench []
    (let [[a b] (into [] [:first :second])]
    (if (= a :first)
      b
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 57.3M | 93.9M | 1.64x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 328 | 64 | 0.20x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### into-empty-tuple2-dynamic

```clojure
(ns bench.snippet.into-empty-tuple2-dynamic)

(defn bench []
    (let [from [:first :second]]
    (let [[a b] (into [] from)]
      (if (= a :first)
        b
        nil))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 56.6M | 192M | 3.40x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 328 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### into-map-small

```clojure
(ns bench.snippet.into-map-small)

(defn bench []
    (let [[a b c d e] (into [] (map identity [:one :two :three :four :five]))]
    (if (= a :one)
      e
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 7.28M | 1.51M | 0.21x |
| **p50 latency (ns)** | 167 | 708 | 4.24x |
| **p95 latency (ns)** | 208 | 750 | 3.61x |
| **Allocation (B/op)** | 832 | 9,576.1 | 11.51x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-first-status

```clojure
(ns bench.snippet.map-first-status)

(defn bench []
    (first (map :status [{:status :ok :id 1} {:status :fail :id 2}])))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 22.7M | 1.54M | 0.07x |
| **p50 latency (ns)** | 42 | 666 | 15.86x |
| **p95 latency (ns)** | 83 | 709 | 8.54x |
| **Allocation (B/op)** | 376 | 9,520.1 | 25.32x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-small-records

```clojure
(ns bench.snippet.map-small-records)

(defn bench []
    (let [ids (map :id [{:id :one :n 1} {:id :two :n 2} {:id :three :n 3}
                      {:id :four :n 4} {:id :five :n 5}])]
    (if (= (first ids) :one)
      (nth ids 4)
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 20.9M | 1.51M | 0.07x |
| **p50 latency (ns)** | 42 | 667 | 15.88x |
| **p95 latency (ns)** | 84 | 750 | 8.93x |
| **Allocation (B/op)** | 368 | 9,728.1 | 26.43x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### into-map-ids

```clojure
(ns bench.snippet.into-map-ids)

(defn bench []
    (let [[a b c d e] (into [] (map :id [{:id :one} {:id :two} {:id :three} {:id :four} {:id :five}]))]
    (if (= a :one)
      e
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 7.24M | 1.52M | 0.21x |
| **p50 latency (ns)** | 125 | 667 | 5.34x |
| **p95 latency (ns)** | 167 | 750 | 4.49x |
| **Allocation (B/op)** | 832 | 9,776.1 | 11.75x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### into-map-ids-dynamic

```clojure
(ns bench.snippet.into-map-ids-dynamic)

(defn bench []
    (let [rows [{:id :one} {:id :two} {:id :three} {:id :four} {:id :five}]]
    (nth (into [] (map :id rows)) 4)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 8.29M | 1.52M | 0.18x |
| **p50 latency (ns)** | 125 | 708 | 5.66x |
| **p95 latency (ns)** | 167 | 875 | 5.24x |
| **Allocation (B/op)** | 808 | 9,408.1 | 11.64x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-first-status-list

```clojure
(ns bench.snippet.map-first-status-list)

(defn bench []
    (first (map :status [{:status :ok} {:status :fail}])))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 29.9M | 1.59M | 0.05x |
| **p50 latency (ns)** | 42 | 667 | 15.88x |
| **p95 latency (ns)** | 84 | 709 | 8.44x |
| **Allocation (B/op)** | 352 | 9,520.1 | 27.05x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-first-status-seq

```clojure
(ns bench.snippet.map-first-status-seq)

(defn bench []
    (first (map :status '({:status :ok} {:status :fail}))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 43.5M | 2.68M | 0.06x |
| **p50 latency (ns)** | 41 | 375 | 9.15x |
| **p95 latency (ns)** | 42 | 417 | 9.93x |
| **Allocation (B/op)** | 240 | 8,768 | 36.53x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-first-status-dynamic

```clojure
(ns bench.snippet.map-first-status-dynamic)

(defn bench []
    (let [rows (vec '({:status :ok} {:status :fail}))]
    (first (map :status rows))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 15.9M | 1.53M | 0.10x |
| **p50 latency (ns)** | 83 | 666 | 8.02x |
| **p95 latency (ns)** | 84 | 709 | 8.44x |
| **Allocation (B/op)** | 768 | 9,360.1 | 12.19x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-filter-status-dynamic

```clojure
(ns bench.snippet.map-filter-status-dynamic)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (first (map :id (filter #(= :ok (:status %)) rows)))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 11.6M | 2.15M | 0.19x |
| **p50 latency (ns)** | 125 | 459 | 3.67x |
| **p95 latency (ns)** | 125 | 542 | 4.34x |
| **Allocation (B/op)** | 1,080 | 10,696 | 9.90x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-filter-status-transduce

```clojure
(ns bench.snippet.map-filter-status-transduce)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (first (into [] (comp (map :id) (filter #(= :ok (:status %)))) rows))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 17.8M | 1.69M | 0.10x |
| **p50 latency (ns)** | 42 | 583 | 13.88x |
| **p95 latency (ns)** | 84 | 667 | 7.94x |
| **Allocation (B/op)** | 736 | 10,024.1 | 13.62x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### row-first-field-dynamic

```clojure
(ns bench.snippet.row-first-field-dynamic)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (let [x (:id (first rows))]
      (if (= x :one)
        x
        nil))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 44.7M | 177M | 3.96x |
| **p50 latency (ns)** | 42 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 432 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### rows-count-dynamic

```clojure
(ns bench.snippet.rows-count-dynamic)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (if (= (count rows) 2)
      :one
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 61.0M | 128M | 2.10x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 328 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-field-rows

```clojure
(ns bench.snippet.map-field-rows)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (first (map :id rows))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 18.2M | 1.53M | 0.08x |
| **p50 latency (ns)** | 83 | 666 | 8.02x |
| **p95 latency (ns)** | 84 | 709 | 8.44x |
| **Allocation (B/op)** | 776 | 9,360.1 | 12.06x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-field-rows-runtime

```clojure
(ns bench.snippet.map-field-rows-runtime)

(defn bench []
    (let [coll (list {:status :ok :id :one} {:status :fail :id :two})
        rows (vec coll)]
    (first (map :id rows))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 17.7M | 1.51M | 0.09x |
| **p50 latency (ns)** | 83 | 666 | 8.02x |
| **p95 latency (ns)** | 84 | 709 | 8.44x |
| **Allocation (B/op)** | 824 | 9,432.1 | 11.45x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-field-rows-nth

```clojure
(ns bench.snippet.map-field-rows-nth)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (nth (map :id rows) 0)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 17.1M | 1.57M | 0.09x |
| **p50 latency (ns)** | 83 | 625 | 7.53x |
| **p95 latency (ns)** | 125 | 667 | 5.34x |
| **Allocation (B/op)** | 776 | 9,360.1 | 12.06x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-field-rows-seq

```clojure
(ns bench.snippet.map-field-rows-seq)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (first (map :id (seq rows)))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 17.2M | 2.63M | 0.15x |
| **p50 latency (ns)** | 83 | 375 | 4.52x |
| **p95 latency (ns)** | 84 | 458 | 5.45x |
| **Allocation (B/op)** | 768 | 8,768 | 11.42x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### filter-rows-dynamic

```clojure
(ns bench.snippet.filter-rows-dynamic)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (first (filter #(= :ok (:status %)) rows))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 15.4M | 10.4M | 0.68x |
| **p50 latency (ns)** | 83 | 125 | 1.51x |
| **p95 latency (ns)** | 84 | 125 | 1.49x |
| **Allocation (B/op)** | 792 | 1,976 | 2.49x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### filter-rows-count-dynamic

```clojure
(ns bench.snippet.filter-rows-count-dynamic)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (if (= (count (filter #(= :ok (:status %)) rows)) 1)
      :one
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 14.0M | 5.14M | 0.37x |
| **p50 latency (ns)** | 83 | 208 | 2.51x |
| **p95 latency (ns)** | 125 | 250 | 2.00x |
| **Allocation (B/op)** | 760 | 2,968 | 3.91x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### filter-after-map-id-dynamic

```clojure
(ns bench.snippet.filter-after-map-id-dynamic)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (first (filter #(= :one %) (map :id rows)))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 12.5M | 1.35M | 0.11x |
| **p50 latency (ns)** | 84 | 750 | 8.93x |
| **p95 latency (ns)** | 125 | 833 | 6.66x |
| **Allocation (B/op)** | 1,048 | 11,328.1 | 10.81x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### filter-after-map-identity-dynamic

```clojure
(ns bench.snippet.filter-after-map-identity-dynamic)

(defn bench []
    (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]
    (first (filter #(= :ok (:status %)) (map identity rows)))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 10.3M | 1.33M | 0.13x |
| **p50 latency (ns)** | 84 | 750 | 8.93x |
| **p95 latency (ns)** | 125 | 833 | 6.67x |
| **Allocation (B/op)** | 1,072 | 11,352.1 | 10.59x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-small-vector

```clojure
(ns bench.snippet.map-small-vector)

(defn bench []
    (let [[a b c d e] (map identity [:one :two :three :four :five])]
    (if (= a :one)
      e
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 20.3M | 1.43M | 0.07x |
| **p50 latency (ns)** | 83 | 667 | 8.04x |
| **p95 latency (ns)** | 84 | 750 | 8.93x |
| **Allocation (B/op)** | 368 | 9,528.1 | 25.89x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-first-small

```clojure
(ns bench.snippet.map-first-small)

(defn bench []
    (first (map identity [:one :two :three :four :five])))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 18.4M | 1.56M | 0.08x |
| **p50 latency (ns)** | 42 | 667 | 15.88x |
| **p95 latency (ns)** | 83 | 875 | 10.54x |
| **Allocation (B/op)** | 392 | 9,432.1 | 24.06x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-first-one

```clojure
(ns bench.snippet.map-first-one)

(defn bench []
    (first (map identity [:one])))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 32.6M | 1.52M | 0.05x |
| **p50 latency (ns)** | 42 | 666 | 15.86x |
| **p95 latency (ns)** | 84 | 833 | 9.92x |
| **Allocation (B/op)** | 352 | 9,416.1 | 26.75x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-identity-vector

```clojure
(ns bench.snippet.map-identity-vector)

(defn bench []
    (first (map identity (vector :one :two :three :four :five))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 15.9M | 1.57M | 0.10x |
| **p50 latency (ns)** | 83 | 666 | 8.02x |
| **p95 latency (ns)** | 125 | 750 | 6.00x |
| **Allocation (B/op)** | 472 | 9,432.1 | 19.98x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### mapv-small-vector

```clojure
(ns bench.snippet.mapv-small-vector)

(defn bench []
    (let [[a b c d e] (mapv identity [:one :two :three :four :five])]
    (if (= a :one)
      e
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 36.4M | 3.07M | 0.08x |
| **p50 latency (ns)** | 42 | 333 | 7.93x |
| **p95 latency (ns)** | 42 | 375 | 8.93x |
| **Allocation (B/op)** | 328 | 3,064 | 9.34x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### ladder-nth5-keywords

```clojure
(ns bench.snippet.ladder-nth5-keywords)

(defn bench []
    (let [x (nth [:one :two :three :four :five] 4)]
    (if (= x :five)
      x
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 329M | 172M | 0.52x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### ladder-first5-keywords

```clojure
(ns bench.snippet.ladder-first5-keywords)

(defn bench []
    (let [x (first [:one :two :three :four :five])]
    (if (= x :one)
      x
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 297M | 179M | 0.60x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### ladder-seq-first5-keywords

```clojure
(ns bench.snippet.ladder-seq-first5-keywords)

(defn bench []
    (let [x (first (seq [:one :two :three :four :five]))]
    (if (= x :one)
      x
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 270M | 176M | 0.65x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### ring-response

```clojure
(ns bench.snippet.ring-response)

(defn bench []
    (let [resp {:status :ok :headers {:content-type "text/plain"} :body "ok"}
        resp2 (assoc resp :headers (assoc (:headers resp) :server "cloffle"))
        resp3 (assoc resp2 :status :created)
        {:keys [status headers body]} resp3]
    (if (and (= status :created)
             (= (:server headers) "cloffle")
             (= (:content-type headers) "text/plain"))
      body
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 32.9M | 18.6M | 0.57x |
| **p50 latency (ns)** | 42 | 42 | 1.00x |
| **p95 latency (ns)** | 42 | 84 | 2.00x |
| **Allocation (B/op)** | 208 | 368 | 1.77x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### hiccup-normalize

```clojure
(ns bench.snippet.hiccup-normalize)

(defn bench
  "PROVISIONAL: `nth` puts a boxed Long index on the measured path, so this snippet's number is
  dominated by index boxing rather than by the lowering layer. Revisit after primitives are
  specialized; until then treat it as a workload sample, not a benchmark."
  []
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
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 215M | 177M | 0.82x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### hiccup-normalize-small

```clojure
(ns bench.snippet.hiccup-normalize-small)

(defn bench []
    (let [tag-name "a"
        content-str "click"
        elem [tag-name {:class "btn" :href "/home"} content-str]
        [tag & items] elem
        [attrs content] (if (map? (first items))
                          [(first items) (first (rest items))]
                          [nil (first items)])]
    (if (and (= tag tag-name)
             (= (:href attrs) "/home"))
      content
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 32.6M | 34.6M | 1.06x |
| **p50 latency (ns)** | 42 | 42 | 1.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 256 | 232 | 0.91x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### identical-nil-dynamic

```clojure
(ns bench.snippet.identical-nil-dynamic)

(defn bench []
  ;; Dynamic vars: non-foldable, no per-op allocation.
  (let [x *print-level*
        y *print-length*]
    (+ (if (identical? x x) 1 0)
       (if (identical? x y) 0 2)
       (if (nil? x) 4 0)
       (if (nil? y) 8 0))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 264M | 13.7M | 0.05x |
| **p50 latency (ns)** | 0 | 83 | - |
| **p95 latency (ns)** | 42 | 125 | 2.98x |
| **Allocation (B/op)** | 0 | 216 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### norm-tuple-nth

```clojure
(ns bench.snippet.norm-tuple-nth)

(defn bench
  "Isolates the slow path from hiccup-normalize: assemble [tag attrs content] from locals,
  then re-read with (nth norm 0/1/2). No elem / destructure / instance? parsing."
  []
  (let [tag "a"
        attrs {:class "btn" :href "/home"}
        content "click"
        norm [tag attrs content]
        final-tag (nth norm 0)
        final-attrs (nth norm 1)
        final-content (nth norm 2)]
    (if (and (= final-tag tag)
             (= (:href final-attrs) "/home"))
      final-content
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 211M | 192M | 0.91x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### kwargs-destructure

```clojure
(ns bench.snippet.kwargs-destructure)

(defn bench []
    (let [opts {:method :post :timeout "500ms"}
        {:keys [method timeout] :or {method :get timeout "1000ms"}} opts]
    (if (= method :post) timeout "none")))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 260M | 164M | 0.63x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### middleware-pipeline

```clojure
(ns bench.snippet.middleware-pipeline)

(defn bench []
    (let [req {:uri "/api/data" :request-method :post :headers {:content-type "application/json"} :body "test-payload"}
        req2 (assoc req :params {:query "search"})
        req3 (assoc req2 :session {:user "alice"})
        {:keys [uri request-method headers params session body]} req3]
    (if (and (= request-method :post)
             (= (:user session) "alice")
             (= (:query params) "search")
             (= (:content-type headers) "application/json"))
      body
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 29.3M | 12.6M | 0.43x |
| **p50 latency (ns)** | 42 | 83 | 1.98x |
| **p95 latency (ns)** | 83 | 125 | 1.51x |
| **Allocation (B/op)** | 184 | 480 | 2.61x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### cond-option-pipeline

```clojure
(ns bench.snippet.cond-option-pipeline)

(defn bench []
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
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 43.1M | 13.5M | 0.31x |
| **p50 latency (ns)** | 41 | 83 | 2.02x |
| **p95 latency (ns)** | 42 | 125 | 2.98x |
| **Allocation (B/op)** | 208 | 448 | 2.15x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### event-enrich

```clojure
(ns bench.snippet.event-enrich)

(defn bench []
    (let [event {:id "evt-101" :type :auth :user "alice" :tenant "org-1"
               :ip "127.0.0.1" :status :ok :timestamp "2026-09-06" :version :v1}
        enriched (assoc event :payload "ok")
        {:keys [id status user payload]} enriched]
    (if (and (= id "evt-101")
             (= status :ok)
             (= user "alice"))
      payload
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 7.33M | 30.4M | 4.15x |
| **p50 latency (ns)** | 125 | 42 | 0.34x |
| **p95 latency (ns)** | 167 | 83 | 0.50x |
| **Allocation (B/op)** | 376 | 240 | 0.64x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### event-sanitize

```clojure
(ns bench.snippet.event-sanitize)

(defn bench []
    (let [event {:id "evt-101" :user "alice" :secret "secret-token" :temp "temp-999" :status :ok}
        sanitized (-> event (dissoc :secret) (dissoc :temp))
        {:keys [id user secret temp status]} sanitized]
    (if (and (= id "evt-101")
             (= status :ok)
             (= user "alice")
             (nil? secret)
             (nil? temp))
      id
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 53.2M | 21.3M | 0.40x |
| **p50 latency (ns)** | 41 | 42 | 1.02x |
| **p95 latency (ns)** | 42 | 84 | 2.00x |
| **Allocation (B/op)** | 152 | 288 | 1.89x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### fixed-str2

```clojure
(ns bench.snippet.fixed-str2)

(defn bench []
    (str :api/route 'handler/name))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 74.4M | 7.35M | 0.10x |
| **p50 latency (ns)** | 41 | 167 | 4.07x |
| **p95 latency (ns)** | 42 | 208 | 4.95x |
| **Allocation (B/op)** | 168 | 864 | 5.14x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### cross-call-map

```clojure
(ns bench.snippet.cross-call-map)

(defn run [s h b]
    (let [resp {:status s :headers h :body b}
           {:keys [status headers body]} resp]
       (if (and (= status :ok)
                (= (:content-type headers) "text/plain"))
         body
         nil)))

(defn bench []
  (run :ok {:content-type "text/plain"} "hello"))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 132M | 66.2M | 0.50x |
| **p50 latency (ns)** | 0 | 41 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 40 | 128 | 3.20x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### cross-call-nested-maps

```clojure
(ns bench.snippet.cross-call-nested-maps)

(defn make []
    {:status :ok
                :headers {:content-type "text/plain"}
                :body "hello"})

(defn enrich [resp]
    (assoc resp :headers (assoc (:headers resp) :server "cloffle")))

(defn consume [resp]
    (let [{:keys [status headers body]} resp]
                    (if (and (= status :ok)
                             (= (:content-type headers) "text/plain")
                             (= (:server headers) "cloffle"))
                      body
                      nil)))

(defn bench []
  (consume (enrich (make))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 42.3M | 22.8M | 0.54x |
| **p50 latency (ns)** | 42 | 42 | 1.00x |
| **p95 latency (ns)** | 42 | 84 | 2.00x |
| **Allocation (B/op)** | 136 | 304 | 2.24x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### cross-call-nested-large

```clojure
(ns bench.snippet.cross-call-nested-large)

(defn make []
    {:id "user-101"
                :type :user
                :tenant-id "org-3"
                :email "avery@example.test"
                :username "avery"
                :status :pending
                :role :admin
                :created-at "2026-01-10"
                :updated-at "2026-09-09"
                :version "v7"
                :locale "en-US"
                :profile {:display-name "Avery" :given-name "Avery" :family-name "Nguyen" :avatar "/avatars/101"}
                :settings {:theme :dark :digest :daily :notifications :enabled :date-format "yyyy-MM-dd"}
                :headers {:content-type "application/json" :accept "application/json"}
                :roles [:admin :clinician :reader :writer :auditor :guest :member]
                :path ["api" "v1" "users" "user-101"]})

(defn enrich [entity]
    (let [headers (assoc (:headers entity) :server "cloffle")
                       roles (conj (:roles entity) :owner)
                       profile (assoc (:profile entity) :display-name "Avery Nguyen")]
                   (-> entity
                       (assoc :status :active)
                       (assoc :headers headers)
                       (assoc :roles roles)
                       (assoc :profile profile))))

(defn consume [entity]
    (let [{:keys [status email profile headers roles path]} entity
                        [r0 r1 r2 r3 r4 r5 r6 r7] roles
                        [p0 p1 p2 p3] path]
                    (if (and (= status :active)
                             (= (:server headers) "cloffle")
                             (= (:content-type headers) "application/json")
                             (= (:display-name profile) "Avery Nguyen")
                             (= r0 :admin)
                             (= r7 :owner)
                             (= p0 "api")
                             (= p3 "user-101"))
                      email
                      nil)))

(defn bench []
  (consume (enrich (make))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 4.78M | 3.39M | 0.71x |
| **p50 latency (ns)** | 209 | 291 | 1.39x |
| **p95 latency (ns)** | 292 | 333 | 1.14x |
| **Allocation (B/op)** | 1,080 | 1,400 | 1.30x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### cross-call-nested-deep

```clojure
(ns bench.snippet.cross-call-nested-deep)

(defn make []
    {:user {:account {:profile {:name "Alice" :role :admin}
                                 :prefs {:theme :dark :locale "en-US"}}
                       :session {:token "t1" :ttl :hour}}})

(defn wrap [doc]
    (assoc doc :envelope {:ok true :via :api}))

(defn enrich [doc]
    (let [user (:user doc)
                       account (:account user)
                       profile (assoc (:profile account) :role :owner)]
                   (assoc doc :user (assoc user :account (assoc account :profile profile)))))

(defn consume [doc]
    (let [account (:account (:user doc))
                        profile (:profile account)
                        prefs (:prefs account)
                        env (:envelope doc)]
                    (if (and (= (:role profile) :owner)
                             (= (:theme prefs) :dark)
                             (= (:ok env) true)
                             (= (:via env) :api))
                      (:name profile)
                      nil)))

(defn bench []
  (consume (enrich (wrap (make)))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 13.4M | 13.4M | 1.00x |
| **p50 latency (ns)** | 83 | 83 | 1.00x |
| **p95 latency (ns)** | 125 | 125 | 1.00x |
| **Allocation (B/op)** | 320 | 816 | 2.55x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### cross-call-nested-rows

```clojure
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
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 15.6M | 19.4M | 1.24x |
| **p50 latency (ns)** | 83 | 83 | 1.00x |
| **p95 latency (ns)** | 125 | 84 | 0.67x |
| **Allocation (B/op)** | 280 | 744 | 2.66x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### cross-call-jsonapi

```clojure
(ns bench.snippet.cross-call-jsonapi)

(defn make []
    {:data {:type "articles"
                       :id "article-101"
                       :attributes {:title "Shape maps" :slug "shape-maps" :status :draft
                                    :author "Avery" :locale "en-US" :category "runtime"}
                       :relationships {:author {:type "people" :id "person-7"}}
                       :links {:self "/articles/article-101"}}
                :included {:type "people" :id "person-7" :name "Avery"}
                :meta {:request-id "req-101" :version "v1"}})

(defn enrich [document]
    (let [data (:data document)
                       attributes (assoc (assoc (:attributes data) :status :published) :summary "ok")]
                   (assoc document :data (assoc data :attributes attributes))))

(defn consume [document]
    (let [data (:data document)
                        attributes (:attributes data)
                        included (:included document)]
                    (if (and (= (:status attributes) :published)
                             (= (:slug attributes) "shape-maps")
                             (= (:id included) "person-7")
                             (= (:id (:author (:relationships data))) "person-7"))
                      (:summary attributes)
                      nil)))

(defn bench []
  (consume (enrich (make))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 14.0M | 12.5M | 0.89x |
| **p50 latency (ns)** | 83 | 84 | 1.01x |
| **p95 latency (ns)** | 125 | 125 | 1.00x |
| **Allocation (B/op)** | 360 | 816 | 2.27x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### cross-call-defn-pipeline

```clojure
(ns bench.snippet.cross-call-defn-pipeline)

(defn make-request []
    {:uri "/api/data"
     :request-method :post
     :headers {:content-type "application/json"}
     :body "payload"})

(defn add-params [req]
    (assoc req :params {:query "search" :limit 10}))

(defn add-session [req]
    (assoc req :session {:user "alice" :role :admin}))

(defn stamp-headers [req]
    (assoc req :headers (assoc (:headers req) :server "cloffle")))

(defn handle-request [req]
    (let [{:keys [request-method headers params session body]} req]
      (if (and (= request-method :post)
               (= (:user session) "alice")
               (= (:role session) :admin)
               (= (:query params) "search")
               (= (:content-type headers) "application/json")
               (= (:server headers) "cloffle"))
        body
        nil)))

(defn bench []
  (handle-request (stamp-headers (add-session (add-params (make-request))))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 18.2M | 7.91M | 0.43x |
| **p50 latency (ns)** | 83 | 125 | 1.51x |
| **p95 latency (ns)** | 84 | 167 | 1.99x |
| **Allocation (B/op)** | 344 | 656 | 1.91x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### cross-call-validation-pipeline

```clojure
(ns bench.snippet.cross-call-validation-pipeline)

(defn make-request []
    {:request-id "req-202"
                :operation :create
                :tenant-id "org-3"
                :headers {:accept "application/fhir+json"
                          :content-type "application/json"}
                :actor {:id "user-7"
                        :role :clinician
                        :scopes [:patient/read :patient/write :audit/read :tenant/admin]}
                :resource {:resource-type :patient
                           :id "patient-101"
                           :profile {:active false :locale "en-US"}
                           :identifiers [{:system "urn:mrn" :value "mrn-101"}
                                         {:system "urn:ssn" :value "ssn-202"}]}
                :body "validated-payload"})

(defn normalize-request [req]
    (let [headers (assoc (:headers req) :content-type "application/fhir+json")
                          resource (:resource req)
                          profile (assoc (:profile resource) :active true)]
                      (-> req
                          (assoc :headers headers)
                          (assoc :resource (assoc resource :profile profile)))))

(defn validate-request [req]
    (let [actor (:actor req)
                         [scope0 scope1 scope2 scope3] (:scopes actor)
                         resource (:resource req)
                         [mrn ssn] (:identifiers resource)
                         actor-valid (and (= (:role actor) :clinician)
                                          (= scope0 :patient/read)
                                          (= scope1 :patient/write)
                                          (= scope2 :audit/read)
                                          (= scope3 :tenant/admin))
                         resource-valid (and (= (:resource-type resource) :patient)
                                             (= (:active (:profile resource)) true)
                                             (= (:system mrn) "urn:mrn")
                                             (= (:system ssn) "urn:ssn"))
                         headers-valid (= (:content-type (:headers req))
                                          "application/fhir+json")]
                     (assoc req :validation {:actor-valid actor-valid
                                             :resource-valid resource-valid
                                             :headers-valid headers-valid})))

(defn authorize-request [req]
    (let [validation (:validation req)
                          authorized (and (= (:actor-valid validation) true)
                                          (= (:resource-valid validation) true)
                                          (= (:headers-valid validation) true))]
                      (assoc req :validation
                             (assoc validation :authorized authorized))))

(defn consume-request [req]
    (let [validation (:validation req)]
                    (if (and (= (:authorized validation) true)
                             (= (:operation req) :create)
                             (= (:tenant-id req) "org-3")
                             (= (:request-id req) "req-202"))
                      (:body req)
                      nil)))

(defn bench []
  (consume-request (authorize-request (validate-request (normalize-request (make-request))))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 5.57M | 5.62M | 1.01x |
| **p50 latency (ns)** | 208 | 208 | 1.00x |
| **p95 latency (ns)** | 209 | 250 | 1.20x |
| **Allocation (B/op)** | 792 | 1,192 | 1.51x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### cross-call-validation-pipeline-threaded

```clojure
(ns bench.snippet.cross-call-validation-pipeline-threaded)

(defn make-request []
  {:request-id "req-202"
   :operation :create
   :tenant-id "org-3"
   :headers {:accept "application/fhir+json"
             :content-type "application/json"}
   :actor {:id "user-7"
           :role :clinician
           :scopes [:patient/read :patient/write :audit/read :tenant/admin]}
   :resource {:resource-type :patient
              :id "patient-101"
              :profile {:active false :locale "en-US"}
              :identifiers [{:system "urn:mrn" :value "mrn-101"}
                            {:system "urn:ssn" :value "ssn-202"}]}
   :body "validated-payload"})

(defn normalize-request [req]
  (let [headers (assoc (:headers req) :content-type "application/fhir+json")
        resource (:resource req)
        profile (assoc (:profile resource) :active true)]
    (-> req
        (assoc :headers headers)
        (assoc :resource (assoc resource :profile profile)))))

(defn validate-request [req]
  (let [actor (:actor req)
        [scope0 scope1 scope2 scope3] (:scopes actor)
        resource (:resource req)
        [mrn ssn] (:identifiers resource)
        actor-valid (and (= (:role actor) :clinician)
                         (= scope0 :patient/read)
                         (= scope1 :patient/write)
                         (= scope2 :audit/read)
                         (= scope3 :tenant/admin))
        resource-valid (and (= (:resource-type resource) :patient)
                            (= (:active (:profile resource)) true)
                            (= (:system mrn) "urn:mrn")
                            (= (:system ssn) "urn:ssn"))
        headers-valid (= (:content-type (:headers req))
                         "application/fhir+json")]
    (assoc req :validation {:actor-valid actor-valid
                            :resource-valid resource-valid
                            :headers-valid headers-valid})))

(defn authorize-request [req]
  (let [validation (:validation req)
        authorized (and (= (:actor-valid validation) true)
                        (= (:resource-valid validation) true)
                        (= (:headers-valid validation) true))]
    (assoc req :validation (assoc validation :authorized authorized))))

(defn consume-request [req]
  (let [validation (:validation req)]
    (if (and (= (:authorized validation) true)
             (= (:operation req) :create)
             (= (:tenant-id req) "org-3")
             (= (:request-id req) "req-202"))
      (:body req)
      nil)))

(defn bench []
  (-> (make-request)
      normalize-request
      validate-request
      authorize-request
      consume-request))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 5.10M | 5.59M | 1.10x |
| **p50 latency (ns)** | 208 | 208 | 1.00x |
| **p95 latency (ns)** | 250 | 250 | 1.00x |
| **Allocation (B/op)** | 792 | 1,192 | 1.51x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### cond-shape-poly

```clojure
(ns bench.snippet.cond-shape-poly)

(defn bench []
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
      nil)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 58.1M | 17.8M | 0.31x |
| **p50 latency (ns)** | 41 | 83 | 2.02x |
| **p95 latency (ns)** | 42 | 84 | 2.00x |
| **Allocation (B/op)** | 160 | 336 | 2.10x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### prim-literal-add

```clojure
(ns bench.snippet.prim-literal-add)

(defn bench
  "Hot long+long through Numbers; Object boundary must still hand back Long."
  []
  (clojure.lang.Numbers/add 1 2))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 354M | 246M | 0.70x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### prim-hinted-locals

```clojure
(ns bench.snippet.prim-hinted-locals)

(defn run [^long a ^long b]
  (clojure.lang.Numbers/add a (clojure.lang.Numbers/multiply b 3)))

(defn bench
  "Hinted long locals with checked arithmetic."
  []
  (run 7 5))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 320M | 141M | 0.44x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### prim-long-loop

```clojure
(ns bench.snippet.prim-long-loop)

(defn bench
  "Tight long loop/recur — allocation should be dominated by the final Long box, not per-iter boxing."
  []
  (loop* [i 0]
    (if (clojure.lang.Numbers/lt i 100)
      (recur (clojure.lang.Numbers/unchecked_inc i))
      i)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 348M | 23.5M | 0.07x |
| **p50 latency (ns)** | 0 | 42 | - |
| **p95 latency (ns)** | 42 | 84 | 2.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### prim-double-loop

```clojure
(ns bench.snippet.prim-double-loop)

(defn bench []
    (loop* [i 0.0]
    (if (clojure.lang.Numbers/lt i 100.0)
      (recur (clojure.lang.Numbers/add i 1.0))
      i)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 24.3M | 16.6M | 0.68x |
| **p50 latency (ns)** | 42 | 42 | 1.00x |
| **p95 latency (ns)** | 83 | 84 | 1.01x |
| **Allocation (B/op)** | 24 | 48 | 2.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### prim-count

```clojure
(ns bench.snippet.prim-count)

(defn bench
  "Compare-performance: use a vector literal so stock Clojure and Cloffle compile the same source.
  Measure RT/count on a constant Indexed vector (host static call / Object boundary)."
  []
  (clojure.lang.RT/count [:a :b :c :d]))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 354M | 235M | 0.66x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### prim-nth

```clojure
(ns bench.snippet.prim-nth)

(defn bench
  "Compare-performance: vector literal for cross-leg parity (not multi-arg RT/vector interop).
  Measure RT/nth on a constant Indexed vector."
  []
  (let* [v [:a :b :c :d :e]]
    (clojure.lang.RT/nth v 3)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 358M | 247M | 0.69x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### prim-java-int

```clojure
(ns bench.snippet.prim-java-int)

(defn bench
  "Host int return must remain Integer at the Object boundary."
  []
  (Integer/parseInt "42"))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 353M | 255M | 0.72x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### prim-object-boundary

```clojure
(ns bench.snippet.prim-object-boundary)

(defn bench
  "Forces a Long box into a collection then reads it back."
  []
  (clojure.lang.RT/nth
   (clojure.lang.RT/conj clojure.lang.PersistentVector/EMPTY
                         (clojure.lang.Numbers/add 10 20))
   0))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 360M | 223M | 0.62x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### Metric Definitions

- **Throughput (ops/sec)**: Sustained execution rate (higher is better).
- **p50 / p95 latency**: 50th and 95th percentile invocation response times (lower is better). JMH sample mode often hits a timer resolution floor (~40 ns on this host), so near-floor values measure the clock more than the snippet.
- **Allocation (B/op) / GC pressure**: Heap bytes allocated per operation (`gc.alloc.rate.norm`). Lower values indicate less GC pressure; near-zero often means Truffle Partial Escape Analysis / scalar replacement.
