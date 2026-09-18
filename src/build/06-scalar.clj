;; Category: scalar replacement / allocation budgets (JMH + Graal BGV diagnostics).
(in-ns 'build)

;; --- Allocation measurement: the scalar replacement gate --------------------
;;
;; The gate is JMH's `gc.alloc.rate.norm`, not the Graal graph, because only the
;; GC profile measures the whole program.
;;
;; Graph evidence cannot carry a gate. `relativeFrequency` is a static estimate
;; scoped to a single compilation unit, and one Clojure pipeline routinely
;; compiles into several units plus interpreted frames. Measured on
;; guestPipelineReduce: its three largest units (the benchmark root,
;; clojure.core_filter, and filter's inner fn) held 34 allocation stubs and not
;; one of them exceeded frequency 0.01, while the benchmark allocated 6168 B/op.
;; The same blindness produced the opposite error earlier, when a 9-node
;; delegating wrapper was analyzed and passed.
;;
;; So graphs answer "what allocated and where did it come from", which is what
;; you need to fix a failure, and the GC profile answers "does it matter", which
;; is what you need to gate one. See HOWTO_SEAFOAM.md.

(defn- read-jmh-json
  "Parse a JMH `-rf json` result file into a vector of maps:
   [{:benchmark \"fully.qualified.Benchmark.method\"
     :mode \"thrpt\"
     :params {\"name\" \"keyword-invoke\"}
     :score .. :score-unit .. :alloc-norm ..}].

   :alloc-norm is `gc.alloc.rate.norm` in B/op, present only when the run used
   `-prof gc`. Jackson is already on the :build classpath via seafoam-jruby."
  [path]
  (let [mapper (com.fasterxml.jackson.databind.ObjectMapper.)
        entries (.readValue mapper (io/file path) java.util.List)
        score (fn [m] (when (instance? java.util.Map m)
                        (try (Double/parseDouble (str (.get ^java.util.Map m "score")))
                             (catch Exception _ nil))))
        to-clj (fn [obj]
                 (when (instance? java.util.Map obj)
                   (into {} (for [[k v] ^java.util.Map obj]
                              [(str k) (str v)]))))]
    (mapv (fn [^java.util.Map entry]
            (let [primary (.get entry "primaryMetric")
                  secondary (.get entry "secondaryMetrics")]
              {:benchmark (str (.get entry "benchmark"))
               :mode (when (.get entry "mode") (str (.get entry "mode")))
               :params (or (to-clj (.get entry "params")) {})
               :score (score primary)
               :score-unit (when (instance? java.util.Map primary)
                             (str (.get ^java.util.Map primary "scoreUnit")))
               :alloc-norm (score (when (instance? java.util.Map secondary)
                                    (.get ^java.util.Map secondary "gc.alloc.rate.norm")))}))
          entries)))

(defn- measure-allocation
  "Run JMH under `-prof gc` and return {:ok bool :results [..] :error msg}.

   Deliberately does not dump Graal graphs: dumping writes hundreds of megabytes,
   dominates the runtime, and perturbs the thing being measured. The diagnostic
   dump is a separate run that only happens once a benchmark has already failed."
  [{:keys [benchmark params mode warmup iterations warmup-time time quiet compile]
    :or {warmup 3 iterations 3 warmup-time "2s" time "3s" quiet true compile true}}]
  (let [json (io/file (System/getProperty "java.io.tmpdir")
                      (format "cloffle-jmh-%d.json" (System/nanoTime)))
        param-args (mapcat (fn [[k v]] ["-p" (str (name k) "=" v)]) params)
        mode-args (when mode ["-bm" (str mode)])
        proc (run-benchmarks {:args (concat [benchmark]
                                            param-args
                                            mode-args
                                            ["-wi" (str warmup) "-i" (str iterations)
                                             "-w" (str warmup-time) "-r" (str time) "-f" "1"
                                             "-prof" "gc"
                                             "-rf" "json" "-rff" (.getAbsolutePath json)])
                              :compile compile
                              :out (if quiet :capture :inherit)
                              :err (if quiet :capture :inherit)})
        ;; Under :quiet the JMH output is captured rather than streamed, so a
        ;; failure would otherwise be reported as a bare exit code. Keep it: the
        ;; cause is usually in there (a concurrent `run-tests` cleaning `target`
        ;; out from under the fork looks exactly like an unexplained exit 1).
        failed (fn [msg]
                 (let [text (str (:out proc) "\n" (:err proc))
                       log (when (seq (clojure.string/trim text))
                             (let [f (io/file (System/getProperty "java.io.tmpdir")
                                              (format "cloffle-jmh-%d.log" (System/nanoTime)))]
                               (try (spit f text) (.getAbsolutePath f)
                                    (catch Throwable _ nil))))
                       informative-line? (fn [line]
                                           (not (or (clojure.string/blank? line)
                                                    (re-find #"Blackhole mode" line)
                                                    (re-find #"modes can be very significant" line)
                                                    (re-find #"Please make sure you use the consistent" line)
                                                    (re-find #"^NOTE:" line))))
                       lines (remove clojure.string/blank? (clojure.string/split-lines text))
                       filtered-lines (clojure.core/filter informative-line? lines)
                       tail-lines (take-last 3 (if (seq filtered-lines) filtered-lines lines))
                       tail (clojure.string/join " | " tail-lines)]
                   {:ok false
                    :error (cond-> msg
                             (seq tail) (str ": " tail)
                             log (str " (full output: " log ")"))
                    :output text}))]
    (try
      (cond
        (not (zero? (:exit proc)))
        (failed (str "JMH exited with code " (:exit proc)))

        (not (.isFile json))
        (failed "JMH wrote no JSON result file")

        :else
        (let [results (read-jmh-json json)]
          (if (empty? results)
            (failed (str "No benchmark matched " benchmark))
            {:ok true :results results})))
      (catch Throwable t
        {:ok false :error (str "could not read JMH results: " (.getMessage t))})
      (finally
        (.delete json)))))

(defn- find-benchmark-result
  "Find entry in JMH results matching `benchmark` (FQN or Class.method suffix),
   and optionally matching `:params` and `:mode`."
  ([results benchmark]
   (find-benchmark-result results benchmark nil nil))
  ([results benchmark expected-params expected-mode]
   (let [suffix (str "." benchmark)
         name-match? (fn [{:keys [benchmark]}]
                       (or (= benchmark suffix)
                           (= benchmark (clojure.string/replace suffix #"^\." ""))
                           (clojure.string/ends-with? benchmark suffix)))
         params-match? (fn [{:keys [params]}]
                         (if (empty? expected-params)
                           true
                           (every? (fn [[k v]]
                                     (= (str (get params (str (name k)))) (str v)))
                                   expected-params)))
         mode-match? (fn [{:keys [mode]}]
                       (if (nil? expected-mode)
                         true
                         (= (str mode) (str expected-mode))))
         candidates (cond->> results
                      true (clojure.core/filter name-match?)
                      (seq expected-params) (clojure.core/filter params-match?))]
     (or (first (clojure.core/filter mode-match? candidates))
         (first candidates)
         ;; Fallback for single-result runs
         (when (= 1 (count results)) (first results))))))

(def ^:private zero-alloc-epsilon
  "B/op at or below this counts as zero. A fully scalar replaced benchmark is
   reported by JMH as ~10^-6 B/op rather than exactly 0."
  1.0)

(defn- alloc-tolerance
  "Allowed overage above a recorded budget: relative for large budgets, with an
   absolute floor so a 24 B/op budget is not held to 2.4 B/op of run-to-run noise."
  [budget]
  (max (* 0.10 (double budget)) 8.0))

(defn- alloc-verdict
  "Compare measured B/op against a budget.

   A nil budget means the benchmark is unbudgeted: report the measurement and
   pass, so the catalog can be filled in incrementally by record-alloc-budgets
   without every unrecorded benchmark failing at once."
  [measured budget]
  (cond
    (nil? measured)
    {:ok false :status :no-measurement
     :message "JMH reported no gc.alloc.rate.norm; was -prof gc dropped?"}

    (nil? budget)
    {:ok true :status :unbudgeted :measured measured
     :message (format "%.1f B/op measured, no :alloc-budget recorded" measured)}

    :else
    (let [budget (double budget)
          limit (+ budget (alloc-tolerance budget))]
      (if (or (<= measured zero-alloc-epsilon) (<= measured limit))
        {:ok true :status :within-budget :measured measured :budget budget
         :message (format "%.1f B/op (budget %.1f)" measured budget)}
        {:ok false :status :over-budget :measured measured :budget budget
         :message (format "%.1f B/op exceeds budget %.1f (limit %.1f)"
                          measured budget limit)}))))

(def ^:private graal-alloc-markers
  ["CommitAllocationNode" "CommitAllocation"
   "NewInstanceNode" "NewArrayNode"
   "new_instance_or_null" "new_array_or_null"
   "TruffleNew" "AllocatingBoxNode" "BoxNode$AllocatingBox"])

(defn- find-phase [phases needle]
  (->> phases
       (filter #(clojure.string/includes? (:name %) needle))
       last))

(defn- marker-hits [text]
  (vec (filter #(clojure.string/includes? (or text "") %) graal-alloc-markers)))

(def ^:private graal-alloc-search-terms
  "Low-tier allocs are ForeignCall descriptors, omitted from node-count histograms."
  ["new_instance_or_null" "new_array_or_null"
   "CommitAllocation" "NewInstanceNode" "NewArrayNode"
   "TruffleNew" "AllocatingBox"])

(defn- bgv-list [^BgvDump dump]
  (mapv (fn [graph]
          {:index (.index graph)
           :name (.name graph)})
        (.listGraphs dump)))

(defn- bgv-describe [^BgvDump dump phase]
  (when phase
    (.describe dump (int (:index phase)))))

(defn- bgv-search-text [^BgvDump dump phase]
  (when phase
    (clojure.string/join
     "\n"
     (for [term graal-alloc-search-terms
           hit (.search dump (int (:index phase)) term)]
       (str term " -> " (.snippet hit))))))

(defn- describe-marker-hits [described]
  (if-not described
    []
    (let [node-classes (keys (.nodeCounts described))]
      (vec
       (filter (fn [marker]
                 (some #(clojure.string/includes? % marker) node-classes))
               graal-alloc-markers)))))

(defn- node-total [described]
  (when described
    (reduce + 0 (vals (into {} (.nodeCounts described))))))

(defn- inspect-bgv
  "Inspect one .bgv compilation in-process. Returns a result map with :ok true/false."
  [bgv]
  (with-open [dump (BgvDump/open (.toPath (io/file bgv)))]
    (let [listed (bgv-list dump)
          ;; A dump the JVM was still writing when it exited stops mid-stream. The
          ;; phases before the cut are valid, but a missing allocation may simply be
          ;; one that was never written, so a pass here would be meaningless.
          truncated? (.isTruncated dump)
          exception? (some #(clojure.string/includes? (:name %) "Exception") listed)
          parsing (find-phase listed "After parsing")
          pea (find-phase listed "FinalPartialEscapePhase")
          low (or (find-phase listed "After low tier")
                  (find-phase listed "/After phase jdk.graal.compiler.core.phases.LowTier"))
          parsing-desc (bgv-describe dump parsing)
          pea-desc (bgv-describe dump pea)
          low-desc (bgv-describe dump low)
          low-search (bgv-search-text dump low)
          pea-search (bgv-search-text dump pea)
          low-hits (vec (distinct (concat (describe-marker-hits low-desc)
                                          (marker-hits low-search))))
          pea-hits (vec (distinct (concat (describe-marker-hits pea-desc)
                                          (marker-hits pea-search))))
          ok (and (not exception?)
                  (not truncated?)
                  (some? low)
                  (empty? low-hits))]
      {:ok ok
       :bgv bgv
       :exception? (boolean exception?)
       :truncated? truncated?
       :low-nodes (node-total low-desc)
       :pea-nodes (node-total pea-desc)
       :phases (count listed)
       :search (str "PEA:\n" pea-search "\nLOW:\n" low-search)
       :parsing (when parsing
                  (assoc parsing
                         :describe (.summary parsing-desc)
                         :hits (describe-marker-hits parsing-desc)))
       :final-pea (when pea (assoc pea :describe (.summary pea-desc) :hits pea-hits))
       :low-tier (when low (assoc low :describe (.summary low-desc) :hits low-hits))})))

;; --- Allocation explanation -------------------------------------------------
;;
;; inspect-bgv answers whether anything still allocates. These answer what, and
;; where it came from, which is what you actually need to fix one.
;;
;; Node selection is by node class rather than by searching property text:
;; search matches anywhere in a node's serialized properties, so a type name
;; matches nodes that merely mention it (searching a real PEA graph for
;; "ClojureClosure" returned 957 hits, nearly all irrelevant). See
;; HOWTO_SEAFOAM.md.

(defn- nodes-of-class
  "Nodes in a phase whose node class is one of `class-names` (simple names)."
  [^BgvDump dump index class-names]
  (->> (.nodes dump (int index))
       (filter (fn [node] (some #(.isClass node ^String %) class-names)))))

(defn- source-frames
  "The nodeSourcePosition chain, innermost first, as \"Class#method\" strings.
   Properties arrive through Jackson as java.util.Map, for which clojure.core/map?
   is false, so the guard tests the Java interface."
  [pos]
  (loop [pos pos, acc [], depth 0]
    (if (or (not (instance? java.util.Map pos)) (>= depth 24))
      acc
      (let [m (get pos "method")
            frame (when (instance? java.util.Map m)
                    (str (get m "declaring_class") "#" (get m "method_name")))]
        (recur (get pos "caller")
               (cond-> acc frame (conj frame))
               (inc depth))))))

(defn- virtual-object
  "Describe one virtual object. VirtualInstanceNode carries `type`;
   VirtualArrayNode carries `componentType` and `length` and has no `type`."
  [^BgvDump dump index node]
  (let [props (.nodeProps dump (int index) (int (.id node)))
        t (get props "type")
        component (get props "componentType")]
    {:id (.id node)
     :array? (some? component)
     :type (or t (str component "[" (get props "length") "]"))
     :length (get props "length")
     :frames (source-frames (get props "nodeSourcePosition"))}))

(defn- relative-frequency [^BgvDump dump index node-id]
  (when-let [f (get (.nodeProps dump (int index) (int node-id)) "relativeFrequency")]
    (try (Double/parseDouble (str f)) (catch Exception _ nil))))

;; --- Why an allocation survived --------------------------------------------
;;
;; The source frames above say what allocated an object. They do not say why PEA
;; had to materialize it, and when the source position is an innocent-looking
;; vector literal or let init that is the only question that matters. The answer
;; is in the object's *usages*: something consumes the materialized value in a
;; place a virtual object cannot go.
;;
;; The case that motivated this: a PersistentTuple2 whose single usage was a phi
;; on the bytecode dispatch loop's LoopBeginNode. A loop phi mixing null with an
;; object cannot stay virtual, and the object was loop-carried only because a
;; destructuring temp stayed live in its frame slot after its last read. Finding
;; that by hand took a one-off probe; this reports it. See HOWTO_SEAFOAM.md.

(defn- node-by-id
  "id -> NodeInfo for one phase. Built once per phase; .nodes is a full scan."
  [^BgvDump dump index]
  (into {} (map (juxt #(.id %) identity)) (.nodes dump (int index))))

(defn- class-of [nodes id]
  (when-let [n (get nodes id)] (str (.nodeClass n))))

(defn- simple-class [cls]
  (last (clojure.string/split (str cls) #"[.$]")))

(defn- merge-kind
  "Whether a phi merges at a loop header or at an ordinary branch join.
   Returns :loop, :branch, or nil when the merge cannot be identified."
  [^BgvDump dump index nodes phi-id]
  (let [merges (->> (.inputs (.nodeEdges dump (int index) (int phi-id)))
                    (keep #(class-of nodes (.from %)))
                    (filter #(or (clojure.string/includes? % "LoopBegin")
                                 (clojure.string/includes? % "Merge"))))]
    (cond
      (some #(clojure.string/includes? % "LoopBegin") merges) :loop
      (seq merges) :branch
      :else nil)))

(defn- allocated-object-nodes
  "AllocatedObjectNodes for one virtual object. PEA rewrites consumers of a
   materialized object to read this node, so the real usages hang off it rather
   than off the VirtualInstanceNode, which by then is only referenced by the
   commit and by deoptimization state."
  [^BgvDump dump index nodes object-id]
  (->> (vals nodes)
       (filter #(.isClass % "AllocatedObjectNode"))
       (filter (fn [n]
                 (some #(= object-id (.from %))
                       (.inputs (.nodeEdges dump (int index) (int (.id n)))))))
       (mapv #(.id %))))

(def ^:private uninformative-usage?
  "Usages that are the materialization itself or only describe the object at a
   safepoint. They are the mechanism, not the cause, so they are noise here."
  #(contains? #{"FrameState" "VirtualObjectState" "MaterializedObjectState"
                "CommitAllocationNode" "AllocatedObjectNode"}
              (simple-class %)))

(defn- survival-reasons
  "Usages of a committed object that explain why it could not stay virtual,
   as [{:id :class :edge :merge}], state-describing usages removed."
  [^BgvDump dump index nodes object-id]
  (->> (cons object-id (allocated-object-nodes dump index nodes object-id))
       (mapcat (fn [src] (.outputs (.nodeEdges dump (int index) (int src)))))
       (keep (fn [e]
               (when-let [cls (class-of nodes (.to e))]
                 (when-not (uninformative-usage? cls)
                   {:id (.to e)
                    :class (simple-class cls)
                    :edge (str (get (.props e) "name"))
                    :merge (when (clojure.string/includes? cls "PhiNode")
                             (merge-kind dump index nodes (.to e)))}))))
       distinct
       vec))

(defn- explain-reason
  "One-line interpretation of a usage, or nil when there is nothing to add."
  [{:keys [class merge]}]
  (cond
    (= :loop merge)
    "loop-carried: a phi at a loop header cannot stay virtual. In guest code that loop is usually the Bytecode DSL dispatch loop, and the object is in the interpreter state at the merge -- check whether a frame slot stays live past its last read."

    (= :branch merge)
    "merged across branches: PEA materializes when the arms disagree on the object."

    (clojure.string/includes? class "Return")
    "returned from this compilation unit, so it escapes by definition."

    (or (clojure.string/includes? class "Invoke")
        (clojure.string/includes? class "Call"))
    "passed to a call that was not inlined; check Call Tree / After Inline."

    (or (clojure.string/includes? class "Store")
        (clojure.string/includes? class "Write"))
    "written to the heap, which is an unconditional escape."

    (or (clojure.string/includes? class "ArrayCopy")
        (clojure.string/includes? class "ArrayFill")
        (clojure.string/includes? class "Unsafe"))
    "consumed by a raw memory operation, which needs a real object with an address."

    :else nil))

(defn- commit-allocations
  "Each CommitAllocationNode with the virtual objects it materializes.

   A commit references its objects through `virtualObjects` input edges and their
   field values through `values` edges; only the former are the objects being
   allocated, so the edge's slot name is what separates them."
  [^BgvDump dump index]
  (let [nodes (node-by-id dump index)
        virtuals (into {} (for [n (nodes-of-class dump index ["VirtualInstanceNode"
                                                              "VirtualArrayNode"])]
                            [(.id n) n]))]
    (for [commit (nodes-of-class dump index ["CommitAllocationNode"])]
      {:id (.id commit)
       :frequency (relative-frequency dump index (.id commit))
       :objects (->> (.inputs (.nodeEdges dump (int index) (int (.id commit))))
                     (filter #(= "virtualObjects" (str (get (.props %) "name"))))
                     (keep #(get virtuals (.from %)))
                     distinct
                     (mapv (fn [n]
                             (assoc (virtual-object dump index n)
                                    :reasons (survival-reasons dump index nodes (.id n))))))})))

(defn- foreign-call-allocations
  "Low-tier surviving allocations. After lowering these are ForeignCallNodes whose
   descriptor names an allocation stub, so they carry no virtual-object list."
  [^BgvDump dump index]
  (for [node (nodes-of-class dump index ["ForeignCallNode"])
        :let [props (.nodeProps dump (int index) (int (.id node)))
              descriptor (str (get props "descriptorName"))]
        :when (or (clojure.string/includes? descriptor "new_instance")
                  (clojure.string/includes? descriptor "new_array"))]
    {:id (.id node)
     :descriptor descriptor
     :frequency (relative-frequency dump index (.id node))
     :frames (source-frames (get props "nodeSourcePosition"))}))

(defn- explain-bgv
  "Itemize what a compilation allocates, at PEA and after low-tier lowering."
  [bgv]
  (with-open [dump (BgvDump/open (.toPath (io/file bgv)))]
    (let [listed (bgv-list dump)
          pea (find-phase listed "FinalPartialEscapePhase")
          low (or (find-phase listed "After low tier")
                  (find-phase listed "/After phase jdk.graal.compiler.core.phases.LowTier"))]
      {:bgv bgv
       :truncated? (.isTruncated dump)
       :pea-phase pea
       :low-phase low
       ;; Everything virtual at PEA, whether or not it is later committed: the
       ;; contrast between this and :commits is what shows PEA doing its job.
       :virtuals (when pea
                   (mapv #(virtual-object dump (:index pea) %)
                         (nodes-of-class dump (:index pea)
                                         ["VirtualInstanceNode" "VirtualArrayNode"])))
       :commits (when pea (vec (commit-allocations dump (:index pea))))
       :foreign-calls (when low (vec (foreign-call-allocations dump (:index low))))})))

(def ^:private cold-path-frequency
  "At or below this, an allocation is on an uncommon or deoptimization path."
  0.05)

(defn- print-frames [frames indent]
  (doseq [[i frame] (map-indexed vector (take 4 frames))]
    (out [:cyan (str indent (apply str (repeat i "  ")) frame)])))

(defn- print-survival-reasons
  "Print why one object had to be materialized. Loop phis come first: they are the
   diagnosis most likely to be actionable and least likely to be guessed."
  [reasons]
  (if (empty? reasons)
    ;; PEA commits an object either because something escapes it or because a
    ;; committed object references it. No escaping usage means the second, so the
    ;; thing to chase is whichever object in this commit does have one.
    (out [:yellow "      no escaping usage of its own; reachable from another object in this commit"])
    (let [ranked (sort-by (fn [{:keys [merge]}] (case merge :loop 0 :branch 1 2)) reasons)]
      (doseq [{:keys [id class edge merge] :as reason} (take 4 ranked)]
        (out [:yellow (str "      used by " class " #" id
                           (when (seq edge) (str " (" edge ")"))
                           (when merge (str " [" (name merge) " merge]")))])
        (when-let [why (explain-reason reason)]
          (out [:yellow (str "        " why)])))
      (when (> (count ranked) 4)
        (out (str "      ... and " (- (count ranked) 4) " more usage(s)"))))))

(defn- print-type-summary [label items]
  (out [:bold (str "  " label " (" (count items) ")")])
  (doseq [[t n] (sort-by (comp - val) (frequencies (map :type items)))]
    (out (str "    " n " x " t))))

(defn- print-explanation [{:keys [truncated? pea-phase low-phase virtuals commits
                                 foreign-calls]}]
  (when truncated?
    (out [:red "  dump is TRUNCATED; phases after the cut are missing entirely"]))

  (if-not pea-phase
    (out [:yellow "  no FinalPartialEscapePhase in this dump (economy tier never runs PEA)"])
    (let [committed (mapcat :objects commits)
          committed-ids (set (map :id committed))
          eliminated (remove #(committed-ids (:id %)) virtuals)]
      (out [:bold.cyan "PEA phase [" (:index pea-phase) "]"])
      (out (str "  " (count virtuals) " virtual objects, "
                (count eliminated) " scalar replaced, "
                (count committed) " committed to the heap"))
      (when (seq eliminated)
        (print-type-summary "eliminated" eliminated))
      (when (empty? committed)
        (out [:green "  nothing survives PEA in this compilation unit"])
        (out [:yellow "  If the benchmark still allocates, the bytes are elsewhere: a sibling"])
        (out [:yellow "  compilation unit, interpreted code, repeated deoptimization, or the host harness."]))
      (when (seq committed)
        (out [:red (str "  SURVIVING (" (count committed) ")")])
        (doseq [{:keys [id frequency objects]} commits
                :when (seq objects)]
          (out [:red (str "  commit " id
                          (when frequency
                            (format " (relativeFrequency %.4f%s)" frequency
                                    (if (<= frequency cold-path-frequency)
                                      ", cold/deopt path" ""))))])
          (doseq [{:keys [type frames reasons]} objects]
            (out (str "    " type))
            (print-frames frames "      ")
            (print-survival-reasons reasons))))))

  (if-not low-phase
    (out [:yellow "  no low-tier phase in this dump"])
    (do
      (out [:bold.cyan "Low tier [" (:index low-phase) "]"])
      (if (empty? foreign-calls)
        (out [:green "  no allocation stubs; nothing reaches the heap"])
        (do
          (out [:red (str "  " (count foreign-calls) " allocation stub call(s)")])
          (doseq [{:keys [id descriptor frequency frames]} foreign-calls]
            (out [:red (str "  " id " " descriptor
                            (when frequency
                              (format " (relativeFrequency %.4f%s)" frequency
                                      (if (<= frequency cold-path-frequency)
                                        ", cold/deopt path" ""))))])
            (print-frames frames "      "))))))
  nil)

;; Below this, a guest graph is too small to hold the work the benchmark times, so a
;; clean result means the compilation being analyzed is not the one doing the work.
(def ^:private suspiciously-small-graph 25)

(defn- print-inspect-result [result]
  (out [:bold (if (:ok result) [:green "PASS"] [:red "FAIL"]) "  " (:bgv result)])
  (when (:exception? result)
    (out [:red "  compilation graph contains Exception"]))
  (when (:truncated? result)
    (out [:red "  dump is TRUNCATED: the JVM exited mid-write, so later phases are missing"])
    (out [:red "  an absent allocation here may simply never have been written"]))
  (when (and (:ok result)
             (:low-nodes result)
             (< (:low-nodes result) suspiciously-small-graph))
    (out [:yellow "  WARNING: low tier has only " (:low-nodes result) " nodes."])
    (out [:yellow "  A graph this small cannot contain a benchmark's work; this pass is likely vacuous."]))
  (doseq [[label phase] [["After parsing" (:parsing result)]
                         ["FinalPartialEscapePhase" (:final-pea result)]
                         ["After low tier" (:low-tier result)]]
          :when phase]
    (out (str "  " label " [" (:index phase) "]: " (:describe phase)
              (when (seq (:hits phase))
                (str "  alloc=" (pr-str (:hits phase)))))))
  ;; On failure, itemize what allocates and where it came from. The raw search
  ;; snippets this used to print were truncated property JSON that named neither
  ;; a type nor a source location.
  (when (or (not (:ok result)) (seq (:hits (:low-tier result))))
    (try
      (print-explanation (explain-bgv (:bgv result)))
      (catch Throwable e
        (out [:yellow "  could not itemize allocations: " (.getMessage e)])
        (when-let [snippets (:search result)]
          (out [:yellow "  alloc search snippets:\n" snippets])))))
  (when (and (not (:ok result)) (empty? (:hits (:low-tier result))) (nil? (:low-tier result)))
    (out [:red "  missing After low tier phase"]))
  result)

(defn- failure-message [result]
  (cond
    (:truncated? result)
    "Dump is truncated (JVM exited mid-write); the graph is incomplete and proves nothing."

    (:exception? result)
    "Compilation graph contains an Exception."

    (nil? (:low-tier result))
    "No low-tier phase in the dump; nothing to check."

    :else
    "Low-tier graph still contains allocation nodes (scalar replacement failed)."))

(defn- list-bgv-files [dir]
  (->> (file-seq (io/file dir))
       (filter #(and (.isFile ^java.io.File %)
                     (clojure.string/ends-with? (.getName ^java.io.File %) ".bgv")))
       (map #(.getPath ^java.io.File %))
       sort))

(defn- select-bgv-files
  "Pick host HotSpot compilations of `method`, or Truffle guest graphs when `guest`.
   `guest-hint` is a substring of the Truffle compilation name (Clojure fn name)."
  [files {:keys [guest method guest-hint]}]
  (let [quoted (java.util.regex.Pattern/quote method)
        method-re (re-pattern quoted)
        hint-re (when (and guest-hint (seq guest-hint))
                  (re-pattern (java.util.regex.Pattern/quote guest-hint)))]
    (filter (fn [p]
              (if guest
                (and (re-find #"TruffleHotSpotCompilation" p)
                     (or (nil? hint-re) (re-find hint-re p)))
                (and (re-find #"HotSpotCompilation-" p)
                     (not (re-find #"HotSpotOSRCompilation" p))
                     (not (re-find #"_jmhTest" p))
                     (re-find method-re p))))
            files)))

(defn- summarize-bgv
  "Cheap per-file summary used to choose between compilations of the same root node."
  [path]
  (let [size (.length (io/file path))]
    (try
      (with-open [dump (BgvDump/open (.toPath (io/file path)))]
        (let [listed (bgv-list dump)]
          {:path path
           :size size
           :graphs (count listed)
           :truncated? (.isTruncated dump)
           :pea? (some? (find-phase listed "FinalPartialEscapePhase"))
           :low? (some? (or (find-phase listed "After low tier")
                            (find-phase listed "/After phase jdk.graal.compiler.core.phases.LowTier")))}))
      (catch Throwable e
        {:path path :size size :graphs 0 :truncated? false :pea? false :low? false
         :error (.getMessage e)}))))

(defn- pick-best-bgv
  "Choose which compilation of a root node to analyze, and summarize the alternatives.

   A hot method is compiled more than once: economy-tier compilations never run PEA,
   and a tier can be recompiled after a deoptimization into a near-empty graph. Only a
   complete compilation that ran PEA and reached low tier says anything about
   allocation, so those are preferred and the largest is taken, more inlined code being
   the better evidence. Selecting on graph count instead, as this once did, picks a
   9-node deoptimized recompile over the real one and reports a vacuous pass.

   Returns [chosen-path candidates]."
  [bgvs]
  (when (seq bgvs)
    (let [candidates (mapv summarize-bgv bgvs)
          usable (filter #(and (not (:truncated? %)) (:pea? %) (:low? %) (nil? (:error %)))
                         candidates)
          chosen (->> (if (seq usable) usable (remove :error candidates))
                      (sort-by (juxt :size :graphs))
                      last)]
      [(:path chosen) candidates])))

(defn- print-candidate-warnings [chosen candidates]
  (when (> (count candidates) 1)
    (out [:cyan "  " (count candidates) " compilations matched; analyzing the largest complete one"]))
  (let [chosen-size (:size (first (filter #(= (:path %) chosen) candidates)))
        truncated (filter :truncated? candidates)
        unreadable (filter :error candidates)
        bigger (filter #(> (:size %) (or chosen-size 0)) (concat truncated unreadable))]
    (doseq [c unreadable]
      (out [:red "  unreadable: " (:path c) " (" (:error c) ")"]))
    (when (seq truncated)
      (out [:yellow "  " (count truncated) " of " (count candidates)
            " matching compilations are truncated and were skipped"]))
    (when (seq bigger)
      (out [:red "  WARNING: " (count bigger) " skipped compilation(s) are LARGER than the one analyzed"])
      (out [:red "  The real hot compilation was probably lost; treat this result as inconclusive."]))))

(defn analyze-graal-graph
  "Inspect a dumped .bgv file for surviving allocations after PEA / low-tier lowering.
   Invoke: clj -T:build analyze-graal-graph :bgv '\"path/to/file.bgv\"'"
  [{:keys [bgv]}]
  (when-not (and (string? bgv) (.isFile (io/file bgv)))
    (throw (ex-info "analyze-graal-graph requires :bgv pointing at an existing .bgv file"
                    {:bgv bgv})))
  (let [result (print-inspect-result (inspect-bgv bgv))]
    (when-not (:ok result)
      (throw (ex-info (failure-message result) result)))
    result))

(def ^:private guest-compilation-hints
  "JMH method name -> substring of TruffleHotSpotCompilation graph file names."
  {"guestShapeMapEphemeralPipeline" "guest-ephemeral-pipeline"
   "guestShapeMapEphemeralInsert" "guest-ephemeral-insert"
   "guestShapeMapEphemeralPromote8" "guest-ephemeral-promote8"
   "guestTupleDestructure" "guest-tuple-destructure"
   "guestListEphemeralPipeline" "guest-list-ephemeral-pipeline"
   "guestLazySeqFirst" "guest-lazy-seq-first"
   "guestConsFirst" "guest-cons-first"
   "guestLazySeqConsFirst" "guest-lazy-seq-cons-first"
   "guestLazySeqApplyFirst" "guest-lazy-seq-apply-first"
   "guestLazySeqWhenSeqFirst" "guest-lazy-seq-when-seq-first"
   "guestMapFirst" "guest-map-first"
   "guestMapSecond" "guest-map-second"
   "guestMappedVectorReduce" "guest-mapped-vector-reduce"
   "guestMappedMapFirst" "guest-mapped-map-first"
   "guestStreamSeqPipeline" "guest-stream-seq-pipeline" ; transducer control pipeline
   "guestPipelineInto" "guest-pipeline-into"
   "guestPipelineVec" "guest-pipeline-vec"
   "guestPipelineReduce" "guest-pipeline-reduce"
   "guestPipelineTakeDrop" "guest-pipeline-take-drop"
   "guestPipelineXformControl" "guest-pipeline-xform-control"
   "guestTuple2Transform" "guest-tuple2-transform"
   "guestKwargsDestructure" "guest-kwargs-destructure"
   "guestMiddlewarePipeline" "guest-middleware-pipeline"
   "guestCondOptionPipeline" "guest-cond-option-pipeline"
   "guestEventEnrichPipeline" "guest-event-enrich"
   "guestShapeMapEphemeralDissoc" "guest-ephemeral-dissoc"
   "guestEventSanitizePipeline" "guest-event-sanitize"
   "guestRingResponsePipeline" "guest-ring-pipeline"
   "guestConstNestedHeaders" "guest-const-nested-headers"
   "guestAllConstNested" "guest-all-const-nested"
   "guestConstIntKeyMap" "guest-const-int-key-map"
   "guestConstNestedDeep" "guest-const-nested-deep"
   "guestConstNestedRingPlus" "guest-const-nested-ring-plus"
   "guestConstNestedApiEnvelope" "guest-const-nested-api-envelope"
   "guestConstNestedMultiSlot" "guest-const-nested-multi-slot"
   "guestConstNestedFanoutBest" "guest-const-nested-fanout-best"
   "guestRingRequestNested" "guest-ring-request-nested"
   "guestFhirPatientNested" "guest-fhir-patient-nested"
   "guestJsonapiDocumentNested" "guest-jsonapi-document-nested"
   "guestAppEntity16" "guest-app-entity-16"
   "guestTypedGithubBytes" "guest-typed-github"
   "guestTypedGithubEarlyBytes" "guest-typed-github-early"
   "guestTypedGithubSumBytes" "guest-typed-github-sum"
   "guestTypedGithubConsume" "guest-typed-github-consume"
   "guestTypedPlaceholderConsume" "guest-typed-placeholder-consume"
   "guestTypedJsonapiConsume" "guest-typed-jsonapi-consume"
   "guestTypedTwitterFirstConsume" "guest-typed-twitter-first-consume"
   "guestTypedPopularApisConsume" "guest-typed-popular-apis-consume"
   "guestTypedTwitterFirstTruffleInput" "guest-typed-twitter-first"
   "guestSimdjsonGithubBytes" "guest-simdjson-github-bytes"
   "guestSimdjsonGithubEarlyBytes" "guest-simdjson-github-early"
   "guestSimdjsonGithubSumBytes" "guest-simdjson-github-sum"
   "guestSimdjsonPlaceholderBytes" "guest-simdjson-placeholder-bytes"
   "guestProjectGithubBytes" "guest-project-github-bytes"
   "guestHiccupNormalizeTag" "guest-hiccup-normalize"
   "guestCheshireFieldNamePipeline" "guest-cheshire-field-name"
   "guestGetInEphemeralPipeline" "guest-get-in-ephemeral-pipeline"})

(defn- dump-graal-graphs
  "Run a benchmark under -Djdk.graal.Dump and pick the compilation to analyze.

   Returns {:ok bool :bgv path :files [..] :candidates [..] :error msg}; never
   throws, because callers use this for diagnosis and a missing graph should not
   mask the failure that sent them here.

   This is the expensive path: dumping writes hundreds of megabytes and slows the
   run by an order of magnitude, so it runs only on a failure or on explicit
   request, never as part of the gate."
  [{:keys [benchmark params mode guest dump-path guest-hint quiet compile
           warmup iterations warmup-time time]
    :or {guest false dump-path "target/graal-dumps-pea" quiet false compile true
         ;; Long enough that the final-tier compilation finishes and its dump is
         ;; fully written before the JVM exits. Shorter runs leave the most
         ;; interesting compilation truncated, and selection then falls back to a
         ;; tiny deoptimized recompile whose clean result means nothing.
         warmup 3 iterations 2 warmup-time "2s" time "3s"}}]
  (let [method (last (clojure.string/split benchmark #"\."))
        snippet-name (get params "name")
        hint (or guest-hint (get guest-compilation-hints method))
        dump-dir (io/file dump-path)
        filter-spec (if guest
                      (if hint
                        (str "*CloffleBytecode*,*" hint "*")
                        "*CloffleBytecode*")
                      (str "*" method "*"))
        name-guest-dump? (and guest (seq snippet-name))
        jvm-dump (str "-Djdk.graal.Dump=:2 -Djdk.graal.PrintGraph=File -Djdk.graal.DumpPath="
                      (.getAbsolutePath dump-dir)
                      " -Djdk.graal.MethodFilter=" filter-spec
                      (when name-guest-dump? " -Dcloffle.bench.nameGuestFn=true"))]
    (b/delete {:path dump-path})
    (.mkdirs dump-dir)
    (when-not quiet
      (out [:bold.cyan "Dumping Graal graphs for " benchmark
            (when guest (str " (guest filter " filter-spec ")"))
            " (wi=" warmup " i=" iterations ")"]))
    (let [proc (run-benchmarks {:args (concat
                                       [benchmark]
                                       ;; Without these a @Param'd benchmark dumps every
                                       ;; value of the parameter: SnippetBenchmark.cloffle
                                       ;; would run all ~40 snippets and mix their graphs
                                       ;; into one directory.
                                       (mapcat (fn [[k v]] ["-p" (str (name k) "=" v)]) params)
                                       (when mode ["-bm" (str mode)])
                                       ["-wi" (str warmup) "-i" (str iterations)
                                        "-w" (str warmup-time) "-r" (str time) "-f" "1"
                                        "-jvmArgsAppend" jvm-dump])
                                :compile compile
                                :out (if quiet :capture :inherit)
                                :err (if quiet :capture :inherit)})]
      (when (and quiet (or (:out proc) (:err proc)))
        (try
          (spit (io/file dump-path "jmh.log") (str (:out proc) "\n" (:err proc)))
          (catch Throwable _ nil)))
      (if-not (zero? (:exit proc))
        {:ok false :error (str "JMH benchmark process exited with code " (:exit proc))}
        (let [files (list-bgv-files dump-path)
              hinted (select-bgv-files files {:guest guest :method method :guest-hint hint})
              ;; Retry without the hint whenever it matched nothing, not only when
              ;; there was no hint. A :snippet evaluates an anonymous guest form, so
              ;; its root is never named after the snippet and the hint cannot match;
              ;; skipping the retry left the only diagnosable dump on disk unselected
              ;; and reported "No matching compilation graph" after a multi-minute run.
              fell-back? (and guest (some? hint) (empty? hinted))
              selected (cond
                         (seq hinted) hinted
                         guest (select-bgv-files files {:guest true :method method
                                                        :guest-hint nil})
                         :else hinted)
              [bgv candidates] (pick-best-bgv selected)]
          (when (and fell-back? bgv (not quiet))
            (out [:yellow "  no guest root matched '" hint
                  "'; analyzing the largest guest compilation instead"])
            (out [:yellow "  (an anonymous root, e.g. a :snippet, is not named after the benchmark"
                  " -- confirm the graph is the one you meant)"]))
          (cond
            (empty? files)
            {:ok false :error (str "No .bgv files written under " dump-path)}

            (not bgv)
            {:ok false :files files
             :error (str "No matching compilation graph for " method
                         (if guest " (TruffleHotSpotCompilation)" " (host HotSpotCompilation)"))}

            :else
            {:ok true :bgv bgv :files files :candidates candidates}))))))

(defn- diagnose-allocations
  "Dump graphs for a failing benchmark and itemize what allocates and where.

   Reporting only. Anything that goes wrong here is printed and swallowed: this
   runs because a benchmark already failed its budget, and losing that verdict to
   a secondary error would be the wrong trade."
  [opts]
  (let [{:keys [ok bgv candidates error]} (dump-graal-graphs opts)]
    (if-not ok
      (do (out [:yellow "  could not dump graphs for diagnosis: " error]) nil)
      (try
        (out [:cyan "  analyzing " bgv])
        (print-candidate-warnings bgv candidates)
        (let [inspected (inspect-bgv bgv)]
          (when (:truncated? inspected)
            (out [:red "  dump is TRUNCATED; phases after the cut are missing"]))
          (when (and (:low-nodes inspected)
                     (< (:low-nodes inspected) suspiciously-small-graph))
            (out [:yellow "  WARNING: low tier has only " (:low-nodes inspected)
                  " nodes; this compilation is too small to hold the benchmark's work"])
            (out [:yellow "  The allocation is likely in a sibling compilation unit or the interpreter."]))
          (print-explanation (explain-bgv bgv))
          (assoc inspected :bgv bgv))
        (catch Throwable t
          (out [:yellow "  could not itemize allocations: " (.getMessage t)])
          nil)))))

(defn- expand-snippet-opts
  "Expand a high-level `:snippet` name/option into SnippetBenchmark opts:
   :benchmark \"SnippetBenchmark.cloffle\", :params {\"name\" <snippet>},
   :mode \"thrpt\", :guest true.

   :guest-hint matches `SnippetBenchmark` named roots when dumps pass
   `-Dcloffle.bench.nameGuestFn=true` (see `dump-graal-graphs`)."
  [{:keys [snippet benchmark params mode guest] :as opts}]
  (if (and snippet (seq (str snippet)))
    (let [sname (str snippet)
          sanitized (clojure.string/replace sname #"[^A-Za-z0-9-]" "-")]
      (assoc opts
             :benchmark (or benchmark "SnippetBenchmark.cloffle")
             :params (merge {"name" sname} params)
             :mode (or mode "thrpt")
             :guest (if (some? guest) guest true)
             :guest-hint (or (:guest-hint opts) (str "snippet-" sanitized))))
    opts))

(defn check-scalar-replacement
  "Fail if a JMH benchmark allocates more than its recorded budget.

   The gate is JMH's `gc.alloc.rate.norm`, because it measures the whole program.
   Graal graph analysis cannot gate: `relativeFrequency` is a static estimate
   scoped to one compilation unit, and a Clojure pipeline compiles into several
   units plus interpreted frames, so a graph can look clean while the benchmark
   allocates kilobytes per operation. Graphs are the diagnosis, printed
   automatically when the gate trips.

   Invoke: clj -T:build check-scalar-replacement :benchmark '\"PersistentTypeScalarReplacementBenchmark.baselineTuple2ScalarReplacement\"'
           clj -T:build check-scalar-replacement :benchmark '\"KeywordMapBenchmark.guestPipelineReduce\"' :guest true :alloc-budget 0
           clj -T:build check-scalar-replacement :snippet '\"keyword-invoke\"' :alloc-budget 0

   Options:
     :benchmark      JMH regex / method name (required unless :snippet is given)
     :snippet        Convenience shortcut: runs SnippetBenchmark.cloffle with -p name=<snippet>
     :params         Map of JMH @Param values, e.g. {\"name\" \"keyword-invoke\"}
     :mode           JMH mode (e.g. \"thrpt\", \"avgt\")
     :alloc-budget   Allowed B/op. Omitted means unbudgeted: the measurement is
                     reported and the check passes with a warning. Populate the
                     catalog with record-alloc-budgets.
     :explain        Dump graphs and itemize allocations on failure (default true)
     :guest true     Diagnose TruffleHotSpotCompilation graphs instead of host methods
     :guest-hint     '\"guest-ephemeral-pipeline\"' to pick a named guest root
     :dump-path      Directory for diagnostic dumps (default \"target/graal-dumps-pea\")
     :quiet true     Suppress JMH stdout (default false)
     :compile false  Skip compile-benchmarks (default true)
     :throw? false   Return the result map instead of throwing (default true)
     :warmup / :iterations / :warmup-time / :time   JMH -wi / -i / -w / -r"
  [raw-opts]
  (let [{:keys [benchmark params mode snippet alloc-budget explain quiet compile throw?
                warmup iterations warmup-time time]
         :or {explain true quiet false compile true throw? true
              warmup 3 iterations 3 warmup-time "2s" time "3s"}
         :as opts} (expand-snippet-opts raw-opts)]
    (when-not (and (string? benchmark) (seq benchmark))
      (throw (ex-info "check-scalar-replacement requires :benchmark or :snippet. To run all known scalar replacement checks, invoke: clj -T:build check-scalar-replacements"
                      {:benchmark benchmark :snippet snippet})))
    (let [method (last (clojure.string/split benchmark #"\."))
          bench-label (str benchmark
                           (when (seq params) (str " " (pr-str params)))
                           (when mode (str " [" mode "]")))
          _ (when-not quiet
              (out [:bold.cyan "Measuring allocation for " bench-label
                    " (-prof gc, wi=" warmup " i=" iterations ")"]))
          measurement (measure-allocation {:benchmark benchmark
                                           :params params
                                           :mode mode
                                           :warmup warmup
                                           :iterations iterations
                                           :warmup-time warmup-time
                                           :time time
                                           :quiet quiet
                                           :compile compile})]
      (if-not (:ok measurement)
        (let [result {:ok false :benchmark benchmark :params params :mode mode
                      :method method :error (:error measurement)}]
          (out [:red "  " (:error measurement)])
          (if throw? (throw (ex-info (:error measurement) result)) result))
        (let [r (find-benchmark-result (:results measurement) benchmark params mode)
              {:keys [score score-unit alloc-norm]} r
              verdict (alloc-verdict alloc-norm alloc-budget)
              result (merge {:benchmark benchmark :params params :mode mode
                             :method method :score score :score-unit score-unit
                             :alloc-norm alloc-norm :alloc-budget alloc-budget}
                            (select-keys verdict [:ok :status :message]))]
          (when score
            (out (format "  %.2f %s" score (or score-unit "ns/op"))))
          (case (:status verdict)
            :within-budget (out [:green "  PASS  " (:message verdict)])
            :unbudgeted (out [:yellow "  PASS  " (:message verdict)
                              " -- run record-alloc-budgets to gate this benchmark"])
            (out [:bold.red "  FAIL  " (:message verdict)]))
          (if (:ok verdict)
            result
            (let [_ (when explain
                      (diagnose-allocations (merge (select-keys opts
                                                                [:guest :guest-hint :dump-path
                                                                 :params :mode
                                                                 :warmup-time :time])
                                                   {:benchmark benchmark
                                                    :quiet true
                                                    :compile false})))
                  error (str bench-label " allocates " (:message verdict))]
              (if throw?
                (throw (ex-info error result))
                (assoc result :error error)))))))))

(defn explain-allocations
  "Explain what a compilation allocates and where each allocation comes from.

   Reporting only: this never fails a build. check-scalar-replacement remains the
   gate that says whether anything allocates; this says what and why, which is
   what you need in order to fix one.

   Invoke with an existing dump:
     clj -T:build explain-allocations :bgv '\"target/graal-dumps-pea/<file>.bgv\"'
   or with a benchmark, which dumps first and picks the right compilation:
     clj -T:build explain-allocations :benchmark '\"KeywordMapBenchmark.guestPipelineReduce\"' :guest true

   Options:
     :bgv        Path to an existing .bgv file
     :benchmark  JMH method name to dump (mutually exclusive with :bgv)
     :snippet    Guest snippet name, e.g. '\"tuple-destructure\"'; dumps use
                 `snippet-<name>` roots via `-Dcloffle.bench.nameGuestFn=true`
     :guest / :guest-hint / :dump-path / :warmup / :iterations / :warmup-time / :time
                 Forwarded to the dump when :benchmark is used

   Reports, per compilation:
     - virtual objects at PEA, split into scalar replaced vs committed to the heap
     - the type of each surviving object, and which CommitAllocationNode commits it
     - the inlined source frames each one came from
     - **why** each survivor was materialized: the usages that force it out of
       virtual form, with loop-header phis called out first
     - low-tier allocation stub calls that survived lowering
     - relativeFrequency, so cold deopt-path allocations are distinguishable

   A clean report here does not mean the benchmark does not allocate: it covers
   one compilation unit, and the allocation may live in a sibling unit or in
   interpreted code. check-scalar-replacement measures the whole program."
  [raw-opts]
  (let [{:keys [bgv benchmark] :as opts} (expand-snippet-opts raw-opts)]
    (when (and bgv benchmark)
      (throw (ex-info "explain-allocations takes :bgv or :benchmark, not both" {})))
    (let [bgv (cond
                bgv (do (when-not (.isFile (io/file bgv))
                          (throw (ex-info "no such .bgv file" {:bgv bgv})))
                        bgv)

                benchmark
                (let [result (dump-graal-graphs opts)]
                  (or (:bgv result)
                      (throw (ex-info (or (:error result) "no compilation graph produced")
                                      {:benchmark benchmark :error (:error result)}))))

                :else
                (throw (ex-info "explain-allocations requires :bgv or :benchmark" {})))
          explanation (explain-bgv bgv)]
      (out [:bold "Allocations in " bgv])
      (print-explanation explanation)
      explanation)))

(def known-scalar-replacement-benchmarks
  "Catalog of known scalar replacement benchmarks across host and guest suites.

   :alloc-budget is the allowed `gc.alloc.rate.norm` in B/op and is what the
   check gates on. A budget of 0 asserts full scalar replacement; a non-zero one
   is a ratchet that pins today's behavior so it cannot regress. Entries without
   a budget are reported and passed with a warning until `record-alloc-budgets`
   fills them in."
  [;; --- Host Baselines: Pure Java ---
   {:benchmark "ScalarReplacementBenchmark.baselineScalarReplacementLiteral"
    :suite :host :guest false :doc "Java SimpleBox literal"}
   {:benchmark "ScalarReplacementBenchmark.baselineScalarReplacementFields"
    :suite :host :guest false :doc "Java SimplePair fields"}

   ;; --- Host Baselines: Clojure Persistent Data Structures ---
   {:benchmark "PersistentTypeScalarReplacementBenchmark.baselineTuple2ScalarReplacement"
    :suite :host :guest false :alloc-budget 0 :doc "PersistentTuple2 field access"}
   {:benchmark "PersistentTypeScalarReplacementBenchmark.baselineTuple3ScalarReplacement"
    :suite :host :guest false :doc "PersistentTuple3 field access"}
   {:benchmark "PersistentTypeScalarReplacementBenchmark.baselineTuple4ScalarReplacement"
    :suite :host :guest false :doc "PersistentTuple4 field access"}
   {:benchmark "PersistentTypeScalarReplacementBenchmark.tuple2AssocNThenNth"
    :suite :host :guest false :doc "PersistentTuple2 assocN rewrite"}
   {:benchmark "PersistentTypeScalarReplacementBenchmark.tuple2ConsThenNth"
    :suite :host :guest false :doc "PersistentTuple2 cons promotion to Tuple3"}
   {:benchmark "PersistentTypeScalarReplacementBenchmark.baselineList2ScalarReplacement"
    :suite :host :guest false :doc "PersistentList2 first + next"}
   {:benchmark "PersistentTypeScalarReplacementBenchmark.list2ConsThenFirst"
    :suite :host :guest false :doc "PersistentList cons chain"}

   ;; --- Host Baselines: PersistentShapeMap ---
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralAssocThenLookup"
    :suite :host :guest false :doc "ShapeMap3 existing-key assoc"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralValAtOnly"
    :suite :host :guest false :doc "ShapeMap3 valAt"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralInsertThenLookup"
    :suite :host :guest false :doc "ShapeMap3 new-key insert"}
   {:benchmark "KeywordMapBenchmark.shapeMap2EphemeralTransitionInsertThenLookup"
    :suite :host :guest false :doc "ShapeMap AssocTransition 2->3"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralTransitionDissocThenLookup"
    :suite :host :guest false :doc "ShapeMap DissocTransition 3->2"}
   {:benchmark "KeywordMapBenchmark.shapeMap8EphemeralTransitionPromoteThenLookup"
    :suite :host :guest false :doc "ShapeMap Promote16Transition 8->9"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralKeywordInvoke"
    :suite :host :guest false :doc "ShapeMap Keyword.invoke"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralNestedValAt"
    :suite :host :guest false :doc "ShapeMap nested valAt"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralWithoutThenLookup"
    :suite :host :guest false :doc "ShapeMap without"}
   {:benchmark "KeywordMapBenchmark.shapeMap16EphemeralAssocThenLookup"
    :suite :host :guest false :doc "ShapeMap16 9-key existing-key assoc"}
   {:benchmark "KeywordMapBenchmark.shapeMap16EphemeralNestedValAt"
    :suite :host :guest false :doc "ShapeMap16 16-key nested valAt"}
   {:benchmark "KeywordMapBenchmark.shapeMap16EphemeralNestedAssocThenLookup"
    :suite :host :guest false :doc "ShapeMap16 nested existing-key assoc"}
   {:benchmark "KeywordMapBenchmark.shapeMap16EphemeralInsertThenLookup"
    :suite :host :guest false :doc "ShapeMap16 new-key insert"}
   {:benchmark "KeywordMapBenchmark.shapeMap5EphemeralValAtOnly"
    :suite :host :guest false :doc "ShapeMap5 cached create + valAt"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralKvReduce"
    :suite :host :guest false :doc "ShapeMap3 unrolled kvreduce"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralReduce"
    :suite :host :guest false :doc "ShapeMap3 MapEntry virtualized reduce"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralGetInHost"
    :suite :host :guest false :doc "ShapeMap3 nested get-in via RT.getIn"}

   ;; --- Guest Cloffle Pipelines (Truffle HotSpot compilations) ---
   {:benchmark "KeywordMapBenchmark.guestShapeMapEphemeralPipeline"
    ;; 24 B/op is the Object[2] a host `IFn.invoke(arg)` hands to the CallTarget. It cannot be
    ;; hoisted into the closure and rewritten per call: that array becomes the callee's
    ;; `frame.getArguments()`, so sharing it races with concurrent calls to the same fn.
    :suite :guest :guest true :hint "guest-ephemeral-pipeline" :alloc-budget 24
    :doc "Guest ShapeMap assoc pipeline"}
   {:benchmark "KeywordMapBenchmark.guestShapeMapEphemeralInsert"
    :suite :guest :guest true :hint "guest-ephemeral-insert" :doc "Guest ShapeMap unrolled insert"}
   {:benchmark "KeywordMapBenchmark.guestShapeMapEphemeralPromote8"
    :suite :guest :guest true :hint "guest-ephemeral-promote8" :doc "Guest ShapeMap 8->9 promote"}
   {:benchmark "KeywordMapBenchmark.guestTupleDestructure"
    :suite :guest :guest true :hint "guest-tuple-destructure" :doc "Guest vector destructuring"}
   {:benchmark "KeywordMapBenchmark.guestListEphemeralPipeline"
    :suite :guest :guest true :hint "guest-list-ephemeral-pipeline" :doc "Guest list ephemeral pipeline"}
   {:benchmark "KeywordMapBenchmark.guestLazySeqFirst"
    :suite :guest :guest true :hint "guest-lazy-seq-first" :doc "Guest LazySeq first"}
   {:benchmark "KeywordMapBenchmark.guestConsFirst"
    :suite :guest :guest true :hint "guest-cons-first" :doc "Guest cons first"}
   {:benchmark "KeywordMapBenchmark.guestLazySeqConsFirst"
    :suite :guest :guest true :hint "guest-lazy-seq-cons-first" :doc "Guest LazySeq cons first"}
   {:benchmark "KeywordMapBenchmark.guestLazySeqApplyFirst"
    :suite :guest :guest true :hint "guest-lazy-seq-apply-first" :doc "Guest LazySeq apply first"}
   {:benchmark "KeywordMapBenchmark.guestLazySeqWhenSeqFirst"
    :suite :guest :guest true :hint "guest-lazy-seq-when-seq-first" :doc "Guest LazySeq when-seq first"}
   {:benchmark "KeywordMapBenchmark.guestMapFirst"
    :suite :guest :guest true :hint "guest-map-first" :doc "Guest map first"}
   {:benchmark "KeywordMapBenchmark.guestMapSecond"
    :suite :guest :guest true :hint "guest-map-second" :doc "Guest map second"}
   {:benchmark "KeywordMapBenchmark.guestMappedVectorReduce"
    :suite :guest :guest true :hint "guest-mapped-vector-reduce" :doc "Guest MappedVectorSeq reduce"}
   {:benchmark "KeywordMapBenchmark.guestMappedMapFirst"
    :suite :guest :guest true :hint "guest-mapped-map-first" :doc "Guest MappedMapSeq first"}
   {:benchmark "KeywordMapBenchmark.guestStreamSeqPipeline"
    :suite :guest :guest true :hint "guest-stream-seq-pipeline" :doc "Guest transducer control pipeline"}
   {:benchmark "KeywordMapBenchmark.guestTuple2Transform"
    :suite :guest :guest true :hint "guest-tuple2-transform" :doc "Guest tuple2 swap & transform"}
   {:benchmark "KeywordMapBenchmark.guestKwargsDestructure"
    :suite :guest :guest true :hint "guest-kwargs-destructure" :doc "Guest kwargs destructure"}
   {:benchmark "KeywordMapBenchmark.guestMiddlewarePipeline"
    :suite :guest :guest true :hint "guest-middleware-pipeline" :alloc-budget 0
    :doc "Guest Ring middleware pipeline"}
   {:benchmark "KeywordMapBenchmark.guestCondOptionPipeline"
    :suite :guest :guest true :hint "guest-cond-option-pipeline" :doc "Guest cond-> options accumulator"}
   {:benchmark "KeywordMapBenchmark.guestEventEnrichPipeline"
    :suite :guest :guest true :hint "guest-event-enrich" :alloc-budget 0
    :doc "Guest 8-key event enrich"}
   {:benchmark "KeywordMapBenchmark.guestShapeMapEphemeralDissoc"
    :suite :guest :guest true :hint "guest-ephemeral-dissoc" :doc "Guest ShapeMap dissoc"
    :alloc-budget 88}
   {:benchmark "KeywordMapBenchmark.guestEventSanitizePipeline"
    :suite :guest :guest true :hint "guest-event-sanitize" :doc "Guest chained dissoc sanitization"
    :alloc-budget 24}
   {:benchmark "KeywordMapBenchmark.guestRingResponsePipeline"
    :suite :guest :guest true :hint "guest-ring-pipeline" :alloc-budget 0
    :doc "Guest Ring response pipeline"}
   {:benchmark "KeywordMapBenchmark.guestRingRequestNested"
    :suite :guest :guest true :hint "guest-ring-request-nested" :doc "Guest 14-key nested Ring request"}
   {:benchmark "KeywordMapBenchmark.guestFhirPatientNested"
    :suite :guest :guest true :hint "guest-fhir-patient-nested" :doc "Guest 16-key nested FHIR Patient"}
   {:benchmark "KeywordMapBenchmark.guestJsonapiDocumentNested"
    :suite :guest :guest true :hint "guest-jsonapi-document-nested" :alloc-budget 0
    :doc "Guest nested JSON:API document"}
   {:benchmark "KeywordMapBenchmark.guestAppEntity16"
    :suite :guest :guest true :hint "guest-app-entity-16" :doc "Guest 16-key nested app entity"}
   ;; Provisional: this indexes with `nth`, so a boxed Long index sits on the measured path and its
   ;; number dominates whatever the lowering layer does. Treat it as a workload sample, not a
   ;; benchmark, until a primitive-specialization pass makes indices measurable.
   {:benchmark "KeywordMapBenchmark.guestHiccupNormalizeTag"
    :suite :guest :guest true :provisional true
    :hint "guest-hiccup-normalize" :doc "Guest Hiccup normalize tag (provisional: boxed index)"}
   {:benchmark "KeywordMapBenchmark.guestCheshireFieldNamePipeline"
    :suite :guest :guest true :hint "guest-cheshire-field-name" :doc "Guest Cheshire field name pipeline"}
   {:benchmark "KeywordMapBenchmark.guestGetInEphemeralPipeline"
    :suite :guest :guest true :hint "guest-get-in-ephemeral-pipeline" :alloc-budget 0
    :doc "Guest inlined get-in ephemeral pipeline"}

   ;; --- Guest Snippet Benchmarks (SnippetBenchmark.cloffle parametrized snippets) ---
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "keyword-invoke"}
    :mode "thrpt"
    :suite :guest :guest true :hint "keyword-invoke"
    :alloc-budget 0
    :doc "Guest snippet keyword-invoke (:b ephemeral map)"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "tuple-destructure"}
    :mode "thrpt"
    :suite :guest :guest true :hint "tuple-destructure"
    :alloc-budget 0
    :doc "Guest snippet tuple-destructure (vector destructuring, fully scalar-replaced)"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "into-empty-tuple2"}
    :mode "thrpt"
    :suite :guest :guest true :hint "into-empty-tuple2"
    :alloc-budget 0
    :doc "Guest snippet (into [] [:first :second]) then destructure; into [] literal folds"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "into-empty-tuple2-dynamic"}
    :mode "thrpt"
    :suite :guest :guest true :hint "into-empty-tuple2-dynamic"
    :alloc-budget 0
    :doc "Dynamic from: (let [from [:first :second]] (into [] from)); literal from in let folds to 0 B/op"}
   {:benchmark "SnippetBenchmark.cloffle"
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-into-map-small"
    :alloc-budget 0
    :doc "Guest snippet (into [] (map identity [:one..:five])) — legacy identity ladder; map + into [] literal folds"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "map-first-status"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-map-first-status"
    :alloc-budget 0
    :doc "Guest snippet (first (map :status [{:status :ok} …])) — keyword map on vector of maps; EVS analyze rewrite"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "map-small-records"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-map-small-records"
    :alloc-budget 0
    :doc "Guest snippet (map :id literal vector of maps) + first/nth; map keyword constant-fold"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "into-map-ids"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-into-map-ids"
    :alloc-budget 0
    :doc "Guest snippet into empty plus map keyword on literal maps; constant-fold"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "into-map-ids-dynamic"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-into-map-ids-dynamic"
    :alloc-budget 0
    :doc "Bisect: (first (map :id rows)); vec quote + map/into constant-fold"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "map-first-status-list"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-map-first-status-list"
    :alloc-budget 0
    :doc "Primary 0 B/op gate: (first (map :status literal vector of maps)); keyword constant-fold + PEA"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "map-first-status-seq"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-map-first-status-seq"
    :alloc-budget 8448
    :doc "Ratchet: (map :status on list literal) lazy-seq path; not a product goal"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "map-first-status-dynamic"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-map-first-status-dynamic"
    :alloc-budget 0
    :doc "Dynamic rows: (first (map :status rows)); vec quote in let folds to 0 B/op"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "map-filter-status-dynamic"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-map-filter-status-dynamic"
    :alloc-budget 0
    :doc "Filter then map :id; FilteredEphemeralVectorSeq + literal fold"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "map-filter-status-transduce"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-map-filter-status-transduce"
    :alloc-budget 0
    :doc "Transducer (comp map filter) + into []; FilteredEVS materialize + literal fold"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "row-first-field-dynamic"}
    :mode "thrpt"
    :suite :guest :guest true :hint "row-first-field-dynamic"
    :alloc-budget 0
    :doc "Bisect floor: (:id (first rows)); vec quote in let init constant-folded"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "rows-count-dynamic"}
    :mode "thrpt"
    :suite :guest :guest true :hint "rows-count-dynamic"
    :alloc-budget 0
    :doc "Bisect: (count rows); vec quote in let init constant-folded"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "map-field-rows"}
    :mode "thrpt"
    :suite :guest :guest true :hint "map-field-rows"
    :alloc-budget 0
    :doc "Bisect: (first (map :id rows)); vec quote + map keyword constant-fold"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "map-field-rows-runtime"}
    :mode "thrpt"
    :suite :guest :guest true :hint "map-field-rows-runtime"
    :alloc-budget 200
    :doc "Runtime rows: (vec (list …)) per op; fold literal list in let + vec(coll) ConstantVectorExpr"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "map-field-rows-nth"}
    :mode "thrpt"
    :suite :guest :guest true :hint "map-field-rows-nth"
    :alloc-budget 0
    :doc "Bisect: (nth (map :id rows) 0); same constant-fold path as map-field-rows"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "map-field-rows-seq"}
    :mode "thrpt"
    :suite :guest :guest true :hint "map-field-rows-seq"
    :alloc-budget 0
    :doc "Bisect: (map :id (seq rows)); vectorish seq wrapper is elided"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "filter-rows-dynamic"}
    :mode "thrpt"
    :suite :guest :guest true :hint "filter-rows-dynamic"
    :alloc-budget 0
    :doc "Bisect: (filter pred rows); FilteredEphemeralVectorSeq on vector"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "filter-rows-count-dynamic"}
    :mode "thrpt"
    :suite :guest :guest true :hint "filter-rows-count-dynamic"
    :alloc-budget 0
    :doc "Bisect: (count (filter pred rows)); vec quote literal + filter fold"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "filter-after-map-id-dynamic"}
    :mode "thrpt"
    :suite :guest :guest true :hint "filter-after-map-id-dynamic"
    :alloc-budget 0
    :doc "Bisect: filter keyword values after (map :id rows); materializeMapThenFilter + fold"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "filter-after-map-identity-dynamic"}
    :mode "thrpt"
    :suite :guest :guest true :hint "filter-after-map-identity-dynamic"
    :alloc-budget 0
    :doc "Bisect: identity map is elided before filtering vectorish rows"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "map-small-vector"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-map-small-vector"
    :alloc-budget 0
    :doc "Legacy identity ladder: (map identity [:one..:five]) then destructure; map constant-folded"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "map-first-small"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-map-first-small"
    :alloc-budget 0
    :doc "Legacy identity ladder: (first (map identity [:one..:five])) — map constant-folded"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "map-first-one"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-map-first-one"
    :alloc-budget 0
    :doc "Legacy identity ladder: (first (map identity [:one])) — map identity literal fold"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "map-identity-vector"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-map-identity-vector"
    :alloc-budget 0
    :doc "Legacy: (first (map identity (vector …))) — literal vector call folds"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "mapv-small-vector"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-mapv-small-vector"
    :alloc-budget 0
    :doc "Ratchet: (mapv identity [:one..:five]) folds to the literal vector"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "ladder-nth5-keywords"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-ladder-nth5-keywords"
    :doc "Ladder: (nth [:one..:five] 4) on constant tuple — RT.nth / VectorNth, no map"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "ladder-first5-keywords"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-ladder-first5-keywords"
    :doc "Ladder: (first [:one..:five]) — seq op on constant vector, no map"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "ladder-seq-first5-keywords"}
    :mode "thrpt"
    :suite :guest :guest true :hint "snippet-ladder-seq-first5-keywords"
    :doc "Ladder: (first (seq [:one..:five])) — RT.seq + first, no map"}
   ;; The assoc escape-probe ladder.
   ;; lowering existed, regardless of whether the result escaped — the tell that the allocation was
   ;; happening behind the shared clojure.core/assoc CallTarget where PEA could not see it.
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "consume-assoc"}
    :mode "thrpt"
    :suite :guest :guest true :hint "consume-assoc"
    :alloc-budget 0
    :doc "Guest snippet consume-assoc (assoc result consumed as a scalar, let-bound source)"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "consume-assoc-no-let"}
    :mode "thrpt"
    :suite :guest :guest true :hint "consume-assoc-no-let"
    :alloc-budget 0
    :doc "Guest snippet consume-assoc-no-let (same, without a frame local)"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "ephemeral-pipeline"}
    :mode "thrpt"
    :suite :guest :guest true :hint "ephemeral-pipeline"
    :alloc-budget 0
    :doc "Guest snippet ephemeral-pipeline (assoc update then keyword read)"}

   ;; The conj probe ladder. These budgets are NOT achievements — they record what conj still
   ;; allocates on the Var path, so the remaining opportunity is visible and cannot silently get
   ;; worse. Lowering conj to a bytecode operation was measured on 2026-09-09 and made every one of
   ;; them worse; see TODO_lowering_layer.md "Phase 2 step 5". Tightening these needs a conj
   ;; TupleConj lowering (direct tuple-grow constructors) — see ConjLoweringIntrospectionTest.
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "consume-conj-vector"}
    :mode "thrpt"
    :suite :guest :guest true :hint "consume-conj-vector"
    :alloc-budget 0
    :doc "Guest snippet consume-conj-vector (conj onto a 2-tuple, result consumed by peek)"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "consume-conj-map"}
    :mode "thrpt"
    :suite :guest :guest true :hint "consume-conj-map"
    :alloc-budget 304
    :doc "Guest snippet consume-conj-map (conj a map onto a map, result read by keyword)"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "consume-conj-list"}
    :mode "thrpt"
    :suite :guest :guest true :hint "consume-conj-list"
    :alloc-budget 128
    :doc "Guest snippet consume-conj-list (conj onto a list, result consumed by first)"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "conj-chain"}
    :mode "thrpt"
    :suite :guest :guest true :hint "conj-chain"
    :alloc-budget 0
    :doc "Guest snippet conj-chain (literal conj ladder folds to a constant vector)"}])

(defn- filter-scalar-replacement-benchmarks
  [benchmarks {:keys [suite filter benchmark snippet]}]
  (let [suite-kw (when suite (keyword (name suite)))
        filter-pattern (or snippet filter benchmark)
        re (when (and filter-pattern (seq (str filter-pattern)))
             (re-pattern (str "(?i)" filter-pattern)))]
    (->> benchmarks
         (clojure.core/filter
          (fn [b]
            (and (or (nil? suite-kw)
                     (= suite-kw :all)
                     (= (:suite b) suite-kw))
                 (or (nil? re)
                     (re-find re (:benchmark b))
                     (re-find re (or (:doc b) ""))
                     (when-let [p (get-in b [:params "name"])]
                       (re-find re p)))))))))

(defn- list-scalar-replacement-benchmarks [matched]
  (out [:bold.cyan (format "\nKnown Scalar Replacement Benchmarks (%d matches):\n" (count matched))])
  (doseq [{:keys [benchmark params mode suite doc alloc-budget]} matched]
    (let [suite-tag (if (= suite :guest) "[:guest]" "[:host] ")
          budget (if alloc-budget (format "%6s B/op" (str alloc-budget)) "  unbudgeted")
          label (str benchmark
                     (when (seq params) (str " " (pr-str params)))
                     (when mode (str " [" mode "]")))]
      (out (format "  %-8s %-11s %-62s %s" suite-tag budget label (or doc "")))))
  nil)

(defn check-scalar-replacements
  "Run every known scalar replacement benchmark against its allocation budget.
   Invoke: clj -T:build check-scalar-replacements
           clj -T:build check-scalar-replacements :suite :host
           clj -T:build check-scalar-replacements :suite :guest
           clj -T:build check-scalar-replacements :filter '\"Tuple\"'
           clj -T:build check-scalar-replacements :list true
   Options:
     :suite     :all (default), :host, or :guest
     :filter    Regex or substring filter on benchmark names
     :benchmark Same as :filter
     :fail-fast Stop on first failure (default false)
     :verbose   Stream full JMH output and allocation diagnosis (default false)
     :list      List matched benchmarks and their budgets without running them
     :explain   Dump graphs and itemize allocations on failure (default true)
     :dump-path Directory for diagnostic Graal IR dumps (default \"target/graal-dumps-pea\")
     :warmup / :iterations / :warmup-time / :time
                Forwarded to each check-scalar-replacement invocation"
  [opts]
  (let [{:keys [fail-fast dump-path verbose list warmup iterations warmup-time time explain]
         :or {fail-fast false dump-path "target/graal-dumps-pea" verbose false list false
              explain true}} opts
        matched (filter-scalar-replacement-benchmarks known-scalar-replacement-benchmarks opts)
        jmh-opts (cond-> {}
                   (some? warmup) (assoc :warmup warmup)
                   (some? iterations) (assoc :iterations iterations)
                   (some? warmup-time) (assoc :warmup-time warmup-time)
                   (some? time) (assoc :time time))]
    (if list
      (list-scalar-replacement-benchmarks matched)
      (do
        (when (empty? matched)
          (throw (ex-info "No scalar replacement benchmarks matched the filter." {:opts opts})))
        (out [:bold.cyan (format "\n===== Running %d Scalar Replacement Check(s) =====\n" (count matched))])
        (when (seq jmh-opts)
          (out [:cyan (str "JMH overrides: " (pr-str jmh-opts))]))
        (compile-benchmarks nil)
        (let [total (count matched)
              results (loop [idx 1
                             remaining matched
                             acc []]
                        (if (empty? remaining)
                          acc
                          (let [{:keys [benchmark guest hint suite alloc-budget]} (first remaining)
                                suite-str (name suite)
                                _ (out (format "[%d/%d] Checking %s (%s)..." idx total benchmark suite-str))
                                t0 (System/currentTimeMillis)
                                res (try
                                      (check-scalar-replacement
                                       (merge jmh-opts
                                              (select-keys (first remaining) [:params :mode])
                                              {:benchmark benchmark
                                               :guest guest
                                               :guest-hint hint
                                               :alloc-budget alloc-budget
                                               :explain explain
                                               :dump-path dump-path
                                               :quiet (not verbose)
                                               :compile false
                                               :throw? false}))
                                      (catch Throwable t
                                        {:ok false :benchmark benchmark :error (.getMessage t)}))
                                elapsed-ms (- (System/currentTimeMillis) t0)
                                ok? (:ok res)
                                entry (assoc res :benchmark benchmark :suite suite :elapsed-ms elapsed-ms)]
                            (if ok?
                              (out [:green (format "  PASS (%.1fs)" (/ elapsed-ms 1000.0))])
                              (do
                                (out [:bold.red (format "  FAIL (%.1fs)" (/ elapsed-ms 1000.0))])
                                (when (:error res)
                                  (out [:red (str "  error: " (:error res))]))))
                            (if (and fail-fast (not ok?))
                              (conj acc entry)
                              (recur (inc idx) (rest remaining) (conj acc entry))))))
              failures (clojure.core/filter #(not (:ok %)) results)
              unbudgeted (clojure.core/filter #(= :unbudgeted (:status %)) results)
              passed (- (count results) (count failures))]
          (out [:bold.cyan (format "\n===== Scalar Replacement Summary (%d/%d passed, %d failed) =====\n"
                                   passed (count results) (count failures))])
          (doseq [{:keys [benchmark ok suite elapsed-ms error alloc-norm alloc-budget]} results]
            (let [tag (if ok [:green "[PASS]"] [:bold.red "[FAIL]"])
                  suite-tag (if (= suite :guest) "[:guest]" "[:host] ")
                  alloc (if alloc-norm
                          (format "%8.1f B/op vs %-9s" alloc-norm
                                  (if alloc-budget (str alloc-budget) "-"))
                          (format "%-24s" "no measurement"))]
              (out (str (ansi/compose tag) " " suite-tag " " alloc
                        (format " %-52s (%.1fs)" benchmark (/ elapsed-ms 1000.0))))
              (when (and (not ok) error)
                (out [:red (str "         " error)]))))
          (when (seq unbudgeted)
            (out [:yellow (format "\n%d benchmark(s) have no :alloc-budget and cannot fail. Record them with:"
                                  (count unbudgeted))])
            (out [:yellow "  clojure -T:build record-alloc-budgets"]))
          (if (seq failures)
            (throw (ex-info (format "Scalar replacement checks failed: %d/%d benchmark(s) exceeded their allocation budget."
                                    (count failures) (count results))
                            {:failures (mapv :benchmark failures)}))
            (do
              (out [:bold.green (format "\nAll %d scalar replacement checks passed!" passed)])
              results)))))))

(defn record-alloc-budgets
  "Measure `gc.alloc.rate.norm` for the catalog and print :alloc-budget entries.

   Reporting only: it never edits build.clj, because a budget is an assertion
   about intended behavior and snapshotting a regression into the catalog would
   silently bless it. Read the output, then paste the budgets you accept.

   Invoke: clj -T:build record-alloc-budgets
           clj -T:build record-alloc-budgets :filter '\"Tuple\"'
           clj -T:build record-alloc-budgets :missing true
           clj -T:build record-alloc-budgets :snippet '\"keyword-invoke\"'

   Options:
     :suite / :filter / :benchmark   Same selection as check-scalar-replacements
     :snippet                        Measure a specific snippet (e.g. \"keyword-invoke\")
     :params                         JMH @Param map when measuring ad-hoc
     :mode                           JMH mode (default \"thrpt\" for snippets)
     :missing true                   Only benchmarks that have no budget yet
     :verbose                        Stream JMH output (default false)
     :warmup / :iterations / :warmup-time / :time   Forwarded to JMH"
  [{:keys [missing verbose warmup iterations warmup-time time snippet] :as raw-opts}]
  (let [opts (expand-snippet-opts raw-opts)
        matched-catalog (cond->> (filter-scalar-replacement-benchmarks
                                  known-scalar-replacement-benchmarks opts)
                          missing (clojure.core/remove :alloc-budget))
        matched (if (and (empty? matched-catalog) (or snippet (:benchmark raw-opts)))
                  [(select-keys opts [:benchmark :params :mode :guest :doc])]
                  matched-catalog)
        jmh-opts (cond-> {:quiet (not verbose) :compile false}
                   (some? warmup) (assoc :warmup warmup)
                   (some? iterations) (assoc :iterations iterations)
                   (some? warmup-time) (assoc :warmup-time warmup-time)
                   (some? time) (assoc :time time))]
    (when (empty? matched)
      (throw (ex-info "No scalar replacement benchmarks matched." {:opts opts})))
    (out [:bold.cyan (format "\n===== Measuring %d benchmark(s) =====\n" (count matched))])
    (compile-benchmarks nil)
    (let [total (count matched)
          measured (doall
                    (for [[idx entry] (map-indexed vector matched)
                          :let [{:keys [benchmark params mode alloc-budget]} entry]]
                      (let [label (str benchmark
                                       (when (seq params) (str " " (pr-str params)))
                                       (when mode (str " [" mode "]")))
                            _ (out (format "[%d/%d] %s..." (inc idx) total label))
                            m (measure-allocation (merge jmh-opts
                                                         {:benchmark benchmark
                                                          :params params
                                                          :mode mode}))
                            r (when (:ok m) (find-benchmark-result (:results m) benchmark params mode))
                            b-op (:alloc-norm r)
                            score (:score r)
                            unit (:score-unit r)]
                        (if b-op
                          (out (format "        %.1f B/op  (%.2f %s)" b-op (or score 0.0) (or unit "ns/op")))
                          (out [:red (str "        no measurement: " (:error m))]))
                        (assoc entry :alloc-norm b-op :old alloc-budget))))]
      (out [:bold.cyan "\n===== Suggested :alloc-budget entries =====\n"])
      (doseq [{:keys [benchmark params mode alloc-norm old]} measured]
        (let [suggested (when alloc-norm
                          (if (<= alloc-norm zero-alloc-epsilon) 0 (Math/round ^double alloc-norm)))
              label (str benchmark (when (seq params) (str " " (pr-str params))))]
          (out (format "  %-62s :alloc-budget %-8s %s"
                       label
                       (if (some? suggested) (str suggested) "?")
                       (cond
                         (nil? alloc-norm) "(no measurement)"
                         (nil? old) "(new)"
                         (= (long old) (long suggested)) "(unchanged)"
                         (> (long suggested) (long old)) (format "(REGRESSION: was %s)" old)
                         :else (format "(improved from %s)" old))))))
      (out [:yellow "\nA budget above 0 pins current behavior; it is not a statement that the allocation is acceptable."])
      measured)))
