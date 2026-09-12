(import '[com.github.thealchemist BgvDump])

(defn find-phase [listed substr]
  (->> listed (filter #(clojure.string/includes? (:name %) substr)) last))

(defn node-counts [^BgvDump dump idx]
  (if (nil? idx)
    {:total 0}
    (let [nodes (.nodes dump (int idx))]
    {:total (count nodes)
     :loops (count (filter #(.isClass % "LoopBeginNode") nodes))
     :value-phis (count (filter #(.isClass % "ValuePhiNode") nodes))
     :merge-phis (count (filter #(.isClass % "MergeNode") nodes))
     :if-nodes (count (filter #(.isClass % "IfNode") nodes))
     :invokes (count (filter #(.isClass % "InvokeNode") nodes))
     :foreign (count (filter #(.isClass % "ForeignCallNode") nodes))
     :frame-without (count (filter #(.isClass % "FrameWithoutBoxing") nodes))
     :commit-alloc (count (filter #(.isClass % "CommitAllocationNode") nodes))
     :virtual-inst (count (filter #(.isClass % "VirtualInstanceNode") nodes))})))

(defn search-count [^BgvDump dump idx term]
  (count (.search dump (int idx) term)))

(defn analyze-bgv [path label]
  (let [f (java.io.File. path)]
    (when-not (.exists f)
      (throw (ex-info "missing bgv" {:path path :label label})))
    (with-open [dump (BgvDump/open (.toPath f))]
      (let [listed (mapv (fn [g] {:index (.index g) :name (.name g)}) (.listGraphs dump))
            low (or (find-phase listed "After low tier")
                    (find-phase listed "/After phase jdk.graal.compiler.core.phases.LowTier"))
            pea (find-phase listed "FinalPartialEscapePhase")
            parse (find-phase listed "After parsing")
            low-idx (int (:index low))
            pea-idx (int (:index pea))]
        {:label label
         :path path
         :bytes (.length f)
         :truncated? (.isTruncated dump)
         :graphs (count listed)
         :low-phase (:name low)
         :parsing (node-counts dump (:index parse))
         :pea (assoc (node-counts dump (:index pea))
                     :continue-at (search-count dump pea-idx "continueAt")
                     :PersistentTuple2 (search-count dump pea-idx "PersistentTuple2"))
         :low (assoc (node-counts dump low-idx)
                     :continue-at (search-count dump low-idx "continueAt")
                     :new_instance (search-count dump low-idx "new_instance_or_null"))}))))

(defn pick-snippet-bgv [dir hint]
  (let [files (->> (file-seq (java.io.File. dir))
                   (filter #(.isFile %))
                   (filter #(.endsWith (.getName %) ".bgv"))
                   (filter #(clojure.string/includes? % "TruffleHotSpotCompilation"))
                   (filter #(clojure.string/includes? % hint))
                   (map #(.getPath %))
                   sort)]
    (or (last files)
        (throw (ex-info "no matching bgv" {:dir dir :hint hint :files (count files)})))))

(defn -main [& args]
  (cond
    (= 2 (count args))
    (println (pr-str (analyze-bgv (first args) (second args))))

    :else
    (doseq [[dir hint label] (partition 3 args)]
      (println "\n====" label "====")
      (println (pr-str (analyze-bgv (pick-snippet-bgv dir hint) label))))))

(apply -main *command-line-args*)
