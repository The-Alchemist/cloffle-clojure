(ns ^{:doc "Re-exports public vars from `clojure.main` (same `Var`s). `main` forwards via `RT/var` at invoke time so it stays correct even if `clojure.main/main` is only bound late."}
  cloffle.main
  (:refer-clojure :exclude [with-bindings])
  (:require clojure.main))

(doseq [[sym ^clojure.lang.Var v] (ns-publics 'clojure.main)
        :when (not= sym 'main)]
  (intern *ns* sym v))

(defn main
  "Same behavior as `clojure.main/main` (delegates at call time)."
  [& args]
  (apply (clojure.lang.RT/var "clojure.main" "main") args))
