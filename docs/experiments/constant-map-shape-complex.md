# Constant map shape — complex benchmarks

Guest workloads (`guest-const-nested-deep`, `guest-const-nested-ring-plus`, `guest-const-nested-api-envelope`, `guest-const-nested-multi-slot`) are in `src/benchmark/resources/keyword-map-benchmark/setup.clj`.

Full before/after tables are in the worktree experiment doc (section **Complex benchmarks** and **Best-case scenario**).

**Best case (worktree faster than main):** run `KeywordMapBenchmark.guestConstNestedRingPlus` — Ring-style constant nested headers + `assoc` + keyword reads (~**6.5%** lower ns/op vs main in 3-fork JMH). Mass fanout benchmarks (`guestConstNestedFanoutBest`, `guestConstInner4FanoutBest`) do **not** beat main.
