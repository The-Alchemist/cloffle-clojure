# Clojure vs Cloffle Performance Comparison

**Date:** 2026-09-16  
**Environment:** Mac OS X (aarch64), Java 25.0.4.1  

Built-in guest samples, compared via direct `IFn.invoke`.

### Summary

| Sample | Clojure (ops/sec) | Cloffle (ops/sec) | Speedup (x) | Clojure p95 (ns) | Cloffle p95 (ns) | Clojure alloc (B/op) | Cloffle alloc (B/op) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `consume-assoc` | 145M | 237M | 1.64 | 42 | 42 | 72 | 0 |
| `consume-assoc-no-let` | 158M | 266M | 1.69 | 42 | 42 | 72 | 0 |
| `assoc-only` | 186M | 198M | 1.06 | 42 | 42 | 72 | 64 |
| `assoc-return-nil` | 147M | 255M | 1.73 | 42 | 42 | 72 | 0 |
| `array-map-lookup` | 336M | 238M | 0.71 | 42 | 42 | 0 | 0 |
| `hash-map-lookup` | 254M | 130M | 0.51 | 42 | 42 | 0 | 0 |
| `shape-map16-lookup` | 306M | 254M | 0.83 | 42 | 42 | 0 | 0 |
| `rt-get-lookup` | 319M | 264M | 0.83 | 42 | 42 | 0 | 0 |
| `keyword-invoke` | 257M | 255M | 0.99 | 42 | 42 | 0 | 0 |
| `nested-get-in` | 50.7M | 177M | 3.50 | 42 | 42 | 64 | 0 |
| `assoc-pipeline` | 155M | 239M | 1.54 | 42 | 42 | 80 | 0 |
| `ephemeral-pipeline` | 150M | 239M | 1.59 | 42 | 42 | 72 | 0 |
| `ephemeral-insert` | 127M | 239M | 1.89 | 42 | 42 | 72 | 0 |
| `ephemeral-promote8` | 5.01M | 263M | 52.53 | 250 | 42 | 648 | 0 |
| `ephemeral-dissoc` | 99.0M | 240M | 2.42 | 42 | 42 | 64 | 0 |
| `consume-conj-vector` | 299M | 176M | 0.59 | 42 | 42 | 0 | 0 |
| `consume-conj-map` | 75.2M | 31.0M | 0.41 | 42 | 83 | 104 | 304 |
| `consume-conj-list` | 160M | 185M | 1.16 | 42 | 42 | 120 | 0 |
| `conj-chain` | 261M | 196M | 0.75 | 42 | 42 | 0 | 0 |
| `tuple-destructure` | 327M | 264M | 0.81 | 42 | 42 | 0 | 0 |
| `lazy-seq-first` | 65.6M | 235M | 3.58 | 42 | 42 | 136 | 0 |
| `lazy-seq-vec-first` | 65.0M | 233M | 3.59 | 42 | 42 | 136 | 0 |
| `tuple2-transform` | 349M | 253M | 0.73 | 42 | 42 | 0 | 0 |
| `into-empty-tuple2` | 57.7M | 238M | 4.13 | 42 | 42 | 328 | 0 |
| `into-empty-tuple2-dynamic` | 57.0M | 237M | 4.17 | 42 | 42 | 328 | 0 |
| `into-map-small` | 8.45M | 253M | 29.92 | 167 | 42 | 832 | 0 |
| `map-first-status` | 28.8M | 235M | 8.18 | 84 | 42 | 352 | 0 |
| `map-small-records` | 15.5M | 213M | 13.74 | 84 | 42 | 392 | 0 |
| `into-map-ids` | 7.32M | 37.7M | 5.16 | 167 | 42 | 832 | 456 |
| `into-map-ids-dynamic` | 7.47M | 45.4M | 6.08 | 167 | 42 | 832 | 88 |
| `map-first-status-list` | 28.8M | 251M | 8.74 | 84 | 42 | 352 | 0 |
| `map-first-status-seq` | 40.9M | 2.65M | 0.06 | 42 | 458 | 240 | 8,768 |
| `map-first-status-dynamic` | 14.1M | 253M | 17.99 | 125 | 42 | 800 | 0 |
| `map-filter-status-dynamic` | 10.0M | 227M | 22.63 | 125 | 42 | 1,104 | 0 |
| `map-filter-status-transduce` | 18.7M | 174M | 9.26 | 84 | 42 | 736 | 0 |
| `row-first-field-dynamic` | 47.2M | 213M | 4.52 | 42 | 42 | 432 | 0 |
| `rows-count-dynamic` | 59.5M | 236M | 3.97 | 42 | 42 | 328 | 0 |
| `map-field-rows` | 16.3M | 236M | 14.51 | 84 | 42 | 768 | 0 |
| `map-field-rows-runtime` | 17.8M | 111M | 6.22 | 125 | 42 | 824 | 32 |
| `map-field-rows-nth` | 18.5M | 263M | 14.20 | 125 | 42 | 744 | 0 |
| `map-field-rows-seq` | 15.9M | 236M | 14.83 | 84 | 42 | 768 | 0 |
| `filter-rows-dynamic` | 17.5M | 176M | 10.05 | 84 | 42 | 760 | 0 |
| `filter-rows-count-dynamic` | 14.0M | 236M | 16.82 | 125 | 42 | 760 | 0 |
| `filter-after-map-id-dynamic` | 12.7M | 176M | 13.86 | 125 | 42 | 1,048 | 0 |
| `filter-after-map-identity-dynamic` | 11.2M | 181M | 16.11 | 125 | 42 | 1,080 | 0 |
| `map-small-vector` | 15.0M | 238M | 15.87 | 84 | 42 | 392 | 0 |
| `map-first-small` | 29.7M | 185M | 6.21 | 84 | 42 | 368 | 0 |
| `map-first-one` | 34.6M | 175M | 5.06 | 84 | 42 | 352 | 0 |
| `map-identity-vector` | 25.4M | 184M | 7.22 | 125 | 42 | 448 | 0 |
| `mapv-small-vector` | 36.6M | 236M | 6.44 | 42 | 42 | 328 | 0 |
| `ladder-nth5-keywords` | 334M | 243M | 0.73 | 42 | 42 | 0 | 0 |
| `ladder-first5-keywords` | 300M | 203M | 0.67 | 42 | 42 | 0 | 0 |
| `ladder-seq-first5-keywords` | 302M | 224M | 0.74 | 42 | 42 | 0 | 0 |
| `ring-response` | 33.0M | 163M | 4.94 | 42 | 42 | 208 | 0 |
| `hiccup-normalize` | 230M | 238M | 1.04 | 42 | 42 | 0 | 0 |
| `hiccup-normalize-small` | 33.1M | 71.3M | 2.16 | 42 | 42 | 256 | 144 |
| `identical-nil-dynamic` | 254M | 14.7M | 0.06 | 42 | 125 | 0 | 216 |
| `norm-tuple-nth` | 266M | 240M | 0.90 | 42 | 42 | 0 | 0 |
| `kwargs-destructure` | 241M | 184M | 0.76 | 42 | 42 | 0 | 0 |
| `middleware-pipeline` | 29.7M | 161M | 5.43 | 42 | 42 | 184 | 0 |
| `cond-option-pipeline` | 44.3M | 190M | 4.29 | 42 | 42 | 208 | 0 |
| `event-enrich` | 7.64M | 204M | 26.74 | 208 | 42 | 416 | 0 |
| `event-sanitize` | 48.5M | 182M | 3.75 | 42 | 42 | 152 | 0 |
| `fixed-str2` | 57.3M | 149M | 2.60 | 42 | 42 | 208 | 64 |
| `cross-call-map` | 147M | 152M | 1.03 | 42 | 42 | 40 | 0 |
| `cross-call-nested-maps` | 25.9M | 175M | 6.77 | 42 | 42 | 136 | 0 |
| `cross-call-nested-large` | 4.70M | 165M | 35.12 | 250 | 42 | 1,080 | 0 |
| `cross-call-nested-deep` | 13.3M | 168M | 12.62 | 125 | 42 | 320 | 0 |
| `cross-call-nested-rows` | 13.7M | 179M | 13.04 | 84 | 42 | 280 | 0 |
| `cross-call-jsonapi` | 12.4M | 177M | 14.24 | 125 | 42 | 360 | 0 |
| `cross-call-defn-pipeline` | 18.2M | 170M | 9.36 | 84 | 42 | 344 | 0 |
| `cross-call-validation-pipeline` | 5.22M | 214M | 41.05 | 250 | 42 | 792 | 0 |
| `cross-call-validation-pipeline-threaded` | 5.66M | 214M | 37.82 | 250 | 42 | 792 | 0 |
| `cond-shape-poly` | 55.0M | 264M | 4.81 | 42 | 42 | 160 | 0 |
| `prim-literal-add` | 358M | 239M | 0.67 | 42 | 42 | 0 | 0 |
| `prim-hinted-locals` | 303M | 157M | 0.52 | 42 | 42 | 0 | 0 |
| `prim-long-loop` | 338M | 23.6M | 0.07 | 42 | 84 | 0 | 0 |
| `prim-double-loop` | 26.3M | 17.8M | 0.68 | 84 | 84 | 24 | 48 |
| `prim-count` | 356M | 253M | 0.71 | 42 | 42 | 0 | 0 |
| `prim-nth` | 359M | 264M | 0.74 | 42 | 42 | 0 | 0 |
| `prim-java-int` | 359M | 243M | 0.68 | 42 | 42 | 0 | 0 |
| `prim-object-boundary` | 358M | 231M | 0.64 | 42 | 42 | 0 | 0 |

_Speedup (x) is Cloffle ÷ Clojure throughput. Latency columns share one unit chosen from the largest p95 across all samples._

### consume-assoc

```clojure
(ns bench.snippet.consume-assoc)

(defn bench []
    (let [m {:a :v1, :b :v2, :c :v3}] (:a (assoc m :b :v999))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 145M | 237M | 1.64x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### consume-assoc-no-let

```clojure
(ns bench.snippet.consume-assoc-no-let)

(defn bench []
    (:a (assoc {:a :v1, :b :v2, :c :v3} :b :v999)))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 158M | 266M | 1.69x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 186M | 198M | 1.06x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 64 | 0.89x |

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
| **Throughput (ops/sec)** | 147M | 255M | 1.73x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### array-map-lookup

```clojure
(ns bench.snippet.array-map-lookup)

(defn bench []
    (get {:a :v1 :b :v2 :c :v3} :b))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 336M | 238M | 0.71x |
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
| **Throughput (ops/sec)** | 254M | 130M | 0.51x |
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
| **Throughput (ops/sec)** | 306M | 254M | 0.83x |
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
| **Throughput (ops/sec)** | 319M | 264M | 0.83x |
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
| **Throughput (ops/sec)** | 257M | 255M | 0.99x |
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
| **Throughput (ops/sec)** | 50.7M | 177M | 3.50x |
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
| **Throughput (ops/sec)** | 155M | 239M | 1.54x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 80 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 150M | 239M | 1.59x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 127M | 239M | 1.89x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 72 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 5.01M | 263M | 52.53x |
| **p50 latency (ns)** | 208 | 0 | 0.00x |
| **p95 latency (ns)** | 250 | 42 | 0.17x |
| **Allocation (B/op)** | 648 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 99.0M | 240M | 2.42x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 64 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### consume-conj-vector

```clojure
(ns bench.snippet.consume-conj-vector)

(defn bench []
    (let [v [:v1 :v2]] (peek (conj v :v3))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 299M | 176M | 0.59x |
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
| **Throughput (ops/sec)** | 75.2M | 31.0M | 0.41x |
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
| **Throughput (ops/sec)** | 160M | 185M | 1.16x |
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
| **Throughput (ops/sec)** | 261M | 196M | 0.75x |
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
| **Throughput (ops/sec)** | 327M | 264M | 0.81x |
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
| **Throughput (ops/sec)** | 65.6M | 235M | 3.58x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 136 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### lazy-seq-vec-first

```clojure
(ns bench.snippet.lazy-seq-vec-first)

(defn bench []
    (first (lazy-seq [:first])))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 65.0M | 233M | 3.59x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 136 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 349M | 253M | 0.73x |
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
| **Throughput (ops/sec)** | 57.7M | 238M | 4.13x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 328 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 57.0M | 237M | 4.17x |
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
| **Throughput (ops/sec)** | 8.45M | 253M | 29.92x |
| **p50 latency (ns)** | 125 | 0 | 0.00x |
| **p95 latency (ns)** | 167 | 42 | 0.25x |
| **Allocation (B/op)** | 832 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-first-status

```clojure
(ns bench.snippet.map-first-status)

(defn bench []
    (first (map :status [{:status :ok :id 1} {:status :fail :id 2}])))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 28.8M | 235M | 8.18x |
| **p50 latency (ns)** | 42 | 0 | 0.00x |
| **p95 latency (ns)** | 84 | 42 | 0.50x |
| **Allocation (B/op)** | 352 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 15.5M | 213M | 13.74x |
| **p50 latency (ns)** | 83 | 0 | 0.00x |
| **p95 latency (ns)** | 84 | 42 | 0.50x |
| **Allocation (B/op)** | 392 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 7.32M | 37.7M | 5.16x |
| **p50 latency (ns)** | 125 | 42 | 0.34x |
| **p95 latency (ns)** | 167 | 42 | 0.25x |
| **Allocation (B/op)** | 832 | 456 | 0.55x |

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
| **Throughput (ops/sec)** | 7.47M | 45.4M | 6.08x |
| **p50 latency (ns)** | 125 | 41 | 0.33x |
| **p95 latency (ns)** | 167 | 42 | 0.25x |
| **Allocation (B/op)** | 832 | 88 | 0.11x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-first-status-list

```clojure
(ns bench.snippet.map-first-status-list)

(defn bench []
    (first (map :status [{:status :ok} {:status :fail}])))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 28.8M | 251M | 8.74x |
| **p50 latency (ns)** | 42 | 0 | 0.00x |
| **p95 latency (ns)** | 84 | 42 | 0.50x |
| **Allocation (B/op)** | 352 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-first-status-seq

```clojure
(ns bench.snippet.map-first-status-seq)

(defn bench []
    (first (map :status '({:status :ok} {:status :fail}))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 40.9M | 2.65M | 0.06x |
| **p50 latency (ns)** | 41 | 375 | 9.15x |
| **p95 latency (ns)** | 42 | 458 | 10.90x |
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
| **Throughput (ops/sec)** | 14.1M | 253M | 17.99x |
| **p50 latency (ns)** | 83 | 0 | 0.00x |
| **p95 latency (ns)** | 125 | 42 | 0.34x |
| **Allocation (B/op)** | 800 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 10.0M | 227M | 22.63x |
| **p50 latency (ns)** | 125 | 0 | 0.00x |
| **p95 latency (ns)** | 125 | 42 | 0.34x |
| **Allocation (B/op)** | 1,104 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 18.7M | 174M | 9.26x |
| **p50 latency (ns)** | 83 | 0 | 0.00x |
| **p95 latency (ns)** | 84 | 42 | 0.50x |
| **Allocation (B/op)** | 736 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 47.2M | 213M | 4.52x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
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
| **Throughput (ops/sec)** | 59.5M | 236M | 3.97x |
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
| **Throughput (ops/sec)** | 16.3M | 236M | 14.51x |
| **p50 latency (ns)** | 83 | 0 | 0.00x |
| **p95 latency (ns)** | 84 | 42 | 0.50x |
| **Allocation (B/op)** | 768 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 17.8M | 111M | 6.22x |
| **p50 latency (ns)** | 83 | 0 | 0.00x |
| **p95 latency (ns)** | 125 | 42 | 0.34x |
| **Allocation (B/op)** | 824 | 32 | 0.04x |

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
| **Throughput (ops/sec)** | 18.5M | 263M | 14.20x |
| **p50 latency (ns)** | 83 | 0 | 0.00x |
| **p95 latency (ns)** | 125 | 42 | 0.34x |
| **Allocation (B/op)** | 744 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 15.9M | 236M | 14.83x |
| **p50 latency (ns)** | 83 | 0 | 0.00x |
| **p95 latency (ns)** | 84 | 42 | 0.50x |
| **Allocation (B/op)** | 768 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 17.5M | 176M | 10.05x |
| **p50 latency (ns)** | 83 | 0 | 0.00x |
| **p95 latency (ns)** | 84 | 42 | 0.50x |
| **Allocation (B/op)** | 760 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 14.0M | 236M | 16.82x |
| **p50 latency (ns)** | 83 | 0 | 0.00x |
| **p95 latency (ns)** | 125 | 42 | 0.34x |
| **Allocation (B/op)** | 760 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 12.7M | 176M | 13.86x |
| **p50 latency (ns)** | 84 | 0 | 0.00x |
| **p95 latency (ns)** | 125 | 42 | 0.34x |
| **Allocation (B/op)** | 1,048 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 11.2M | 181M | 16.11x |
| **p50 latency (ns)** | 125 | 0 | 0.00x |
| **p95 latency (ns)** | 125 | 42 | 0.34x |
| **Allocation (B/op)** | 1,080 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 15.0M | 238M | 15.87x |
| **p50 latency (ns)** | 42 | 0 | 0.00x |
| **p95 latency (ns)** | 84 | 42 | 0.50x |
| **Allocation (B/op)** | 392 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-first-small

```clojure
(ns bench.snippet.map-first-small)

(defn bench []
    (first (map identity [:one :two :three :four :five])))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 29.7M | 185M | 6.21x |
| **p50 latency (ns)** | 83 | 0 | 0.00x |
| **p95 latency (ns)** | 84 | 42 | 0.50x |
| **Allocation (B/op)** | 368 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-first-one

```clojure
(ns bench.snippet.map-first-one)

(defn bench []
    (first (map identity [:one])))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 34.6M | 175M | 5.06x |
| **p50 latency (ns)** | 42 | 0 | 0.00x |
| **p95 latency (ns)** | 84 | 42 | 0.50x |
| **Allocation (B/op)** | 352 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### map-identity-vector

```clojure
(ns bench.snippet.map-identity-vector)

(defn bench []
    (first (map identity (vector :one :two :three :four :five))))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 25.4M | 184M | 7.22x |
| **p50 latency (ns)** | 83 | 0 | 0.00x |
| **p95 latency (ns)** | 125 | 42 | 0.34x |
| **Allocation (B/op)** | 448 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 36.6M | 236M | 6.44x |
| **p50 latency (ns)** | 42 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 328 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 334M | 243M | 0.73x |
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
| **Throughput (ops/sec)** | 300M | 203M | 0.67x |
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
| **Throughput (ops/sec)** | 302M | 224M | 0.74x |
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
| **Throughput (ops/sec)** | 33.0M | 163M | 4.94x |
| **p50 latency (ns)** | 42 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 208 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 230M | 238M | 1.04x |
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
| **Throughput (ops/sec)** | 33.1M | 71.3M | 2.16x |
| **p50 latency (ns)** | 42 | 41 | 0.98x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 256 | 144 | 0.56x |

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
| **Throughput (ops/sec)** | 254M | 14.7M | 0.06x |
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
| **Throughput (ops/sec)** | 266M | 240M | 0.90x |
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
| **Throughput (ops/sec)** | 241M | 184M | 0.76x |
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
| **Throughput (ops/sec)** | 29.7M | 161M | 5.43x |
| **p50 latency (ns)** | 42 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 184 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 44.3M | 190M | 4.29x |
| **p50 latency (ns)** | 42 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 208 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 7.64M | 204M | 26.74x |
| **p50 latency (ns)** | 125 | 0 | 0.00x |
| **p95 latency (ns)** | 208 | 42 | 0.20x |
| **Allocation (B/op)** | 416 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 48.5M | 182M | 3.75x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 152 | 0 | 0.00x |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### fixed-str2

```clojure
(ns bench.snippet.fixed-str2)

(defn bench []
    (str :api/route 'handler/name))
```

| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |
| :--- | ---: | ---: | ---: |
| **Throughput (ops/sec)** | 57.3M | 149M | 2.60x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 208 | 64 | 0.31x |

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
| **Throughput (ops/sec)** | 147M | 152M | 1.03x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 40 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 25.9M | 175M | 6.77x |
| **p50 latency (ns)** | 42 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 136 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 4.70M | 165M | 35.12x |
| **p50 latency (ns)** | 208 | 0 | 0.00x |
| **p95 latency (ns)** | 250 | 42 | 0.17x |
| **Allocation (B/op)** | 1,080 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 13.3M | 168M | 12.62x |
| **p50 latency (ns)** | 84 | 0 | 0.00x |
| **p95 latency (ns)** | 125 | 42 | 0.34x |
| **Allocation (B/op)** | 320 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 13.7M | 179M | 13.04x |
| **p50 latency (ns)** | 83 | 0 | 0.00x |
| **p95 latency (ns)** | 84 | 42 | 0.50x |
| **Allocation (B/op)** | 280 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 12.4M | 177M | 14.24x |
| **p50 latency (ns)** | 83 | 0 | 0.00x |
| **p95 latency (ns)** | 125 | 42 | 0.34x |
| **Allocation (B/op)** | 360 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 18.2M | 170M | 9.36x |
| **p50 latency (ns)** | 83 | 0 | 0.00x |
| **p95 latency (ns)** | 84 | 42 | 0.50x |
| **Allocation (B/op)** | 344 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 5.22M | 214M | 41.05x |
| **p50 latency (ns)** | 208 | 0 | 0.00x |
| **p95 latency (ns)** | 250 | 42 | 0.17x |
| **Allocation (B/op)** | 792 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 5.66M | 214M | 37.82x |
| **p50 latency (ns)** | 208 | 0 | 0.00x |
| **p95 latency (ns)** | 250 | 42 | 0.17x |
| **Allocation (B/op)** | 792 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 55.0M | 264M | 4.81x |
| **p50 latency (ns)** | 41 | 0 | 0.00x |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 160 | 0 | 0.00x |

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
| **Throughput (ops/sec)** | 358M | 239M | 0.67x |
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
| **Throughput (ops/sec)** | 303M | 157M | 0.52x |
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
| **Throughput (ops/sec)** | 338M | 23.6M | 0.07x |
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
| **Throughput (ops/sec)** | 26.3M | 17.8M | 0.68x |
| **p50 latency (ns)** | 42 | 83 | 1.98x |
| **p95 latency (ns)** | 84 | 84 | 1.00x |
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
| **Throughput (ops/sec)** | 356M | 253M | 0.71x |
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
| **Throughput (ops/sec)** | 359M | 264M | 0.74x |
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
| **Throughput (ops/sec)** | 359M | 243M | 0.68x |
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
| **Throughput (ops/sec)** | 358M | 231M | 0.64x |
| **p50 latency (ns)** | 0 | 0 | - |
| **p95 latency (ns)** | 42 | 42 | 1.00x |
| **Allocation (B/op)** | 0 | 0 | - |

_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._

### Metric Definitions

- **Throughput (ops/sec)**: Sustained execution rate (higher is better).
- **p50 / p95 latency**: 50th and 95th percentile invocation response times (lower is better). JMH sample mode often hits a timer resolution floor (~40 ns on this host), so near-floor values measure the clock more than the snippet.
- **Allocation (B/op) / GC pressure**: Heap bytes allocated per operation (`gc.alloc.rate.norm`). Lower values indicate less GC pressure; near-zero often means Truffle Partial Escape Analysis / scalar replacement.
