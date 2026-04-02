(ns clojure.test-clojure.check-specs
  "Repro for enabling RT.CHECK_SPECS in Cloffle.

  RT.CHECK_SPECS is static final false — these tests bypass the flag and
  exercise the same code paths by hand (loading spec, resolving
  macroexpand-check, calling it) so you can iterate on the fix without
  recompiling RT.java each time.

  --- Blocking issue (CHECK_SPECS=true during RT.init) ---

  When CHECK_SPECS=true, the very first macro form in core.clj
  — (ns clojure.core) — triggers:

    checkSpecsAt → ensureMacroCheck → RT.load(\"clojure/spec/alpha\")

  At that point `ns` is still bootNamespace (a 3-arg AFn in RT.java),
  so the (ns clojure.spec.alpha (:refer-clojure …) (:require …)) form
  (5 args with &form/&env) throws:

    ArityException: Wrong number of args (5) passed to: clojure.lang.RT/3

  --- Fix direction ---

  Two viable approaches:

  1. Defer checkSpecsAt during RT.doInit — e.g. gate on a flag that is
     set *after* core.clj finishes loading.

  2. Have ensureMacroCheck load spec through the host Clojure classloader
     (the Maven JAR already on the classpath) instead of through
     CloffleCompiler.

  --- Post-bootstrap path (tested below) ---

  After core.clj fully loads, the real ns macro is installed and spec
  can load normally.  These tests exercise exactly that path.

  Run:  clj -T:build run-clj-tests :only-namespace \"clojure.test-clojure.check-specs\""
  (:use clojure.test))

;; ---------- Step 1: load clojure.spec.alpha through Cloffle ----------

(deftest step1-load-spec-alpha
  (testing "clojure.spec.alpha loads after core is fully bootstrapped"
    (require 'clojure.spec.alpha)
    (is (find-ns 'clojure.spec.alpha)
        "clojure.spec.alpha namespace should exist")))

;; ---------- Step 2: load clojure.core.specs.alpha ----------

(deftest step2-load-core-specs-alpha
  (testing "clojure.core.specs.alpha loads (specs for defn, fn, let, ns, …)"
    (require 'clojure.spec.alpha)
    (require 'clojure.core.specs.alpha)
    (is (find-ns 'clojure.core.specs.alpha)
        "clojure.core.specs.alpha namespace should exist")))

;; ---------- Step 3: resolve macroexpand-check ----------

(deftest step3-resolve-macroexpand-check
  (testing "clojure.spec.alpha/macroexpand-check is a bound Var"
    (require 'clojure.spec.alpha)
    (require 'clojure.core.specs.alpha)
    (let [v (find-var 'clojure.spec.alpha/macroexpand-check)]
      (is (some? v) "macroexpand-check Var should exist")
      (is (some? (deref v)) "macroexpand-check should be bound"))))

;; ---------- Step 4: macroexpand-check rejects malformed defn ----------

(deftest step4-reject-bad-defn
  (testing "(defn foo a) — missing param vector — should fail spec"
    (require 'clojure.spec.alpha)
    (require 'clojure.core.specs.alpha)
    (let [check-fn (deref (find-var 'clojure.spec.alpha/macroexpand-check))
          defn-var  #'clojure.core/defn
          bad-args  '(foo a)]
      (is (thrown-with-msg?
            Exception
            #"did not conform to spec"
            (check-fn defn-var bad-args))
          "macroexpand-check should throw 'did not conform to spec'"))))

;; ---------- Step 5: macroexpand-check accepts valid defn ----------

(deftest step5-accept-good-defn
  (testing "(defn foo [x] x) — valid defn — should pass spec"
    (require 'clojure.spec.alpha)
    (require 'clojure.core.specs.alpha)
    (let [check-fn (deref (find-var 'clojure.spec.alpha/macroexpand-check))
          defn-var  #'clojure.core/defn
          good-args '(foo [x] x)]
      (is (nil? (check-fn defn-var good-args))
          "macroexpand-check should return nil for valid defn"))))

;; ---------- Step 6: macroexpand-check rejects malformed fn ----------

(deftest step6-reject-bad-fn
  (testing "(fn \"a\" a) — string as second arg — should fail spec"
    (require 'clojure.spec.alpha)
    (require 'clojure.core.specs.alpha)
    (let [check-fn (deref (find-var 'clojure.spec.alpha/macroexpand-check))
          fn-var    #'clojure.core/fn
          bad-args  '("a" a)]
      (is (thrown-with-msg?
            Exception
            #"did not conform to spec"
            (check-fn fn-var bad-args))
          "macroexpand-check should throw 'did not conform to spec'"))))

;; ---------- Step 7: macroexpand-check rejects keywords in let bindings ----------

(deftest step7-reject-keyword-in-let
  (testing "(let [:a 1] a) — keyword in binding — should fail spec"
    (require 'clojure.spec.alpha)
    (require 'clojure.core.specs.alpha)
    (let [check-fn (deref (find-var 'clojure.spec.alpha/macroexpand-check))
          let-var   #'clojure.core/let
          bad-args  '([:a 1] a)]
      (is (thrown-with-msg?
            Exception
            #"did not conform to spec"
            (check-fn let-var bad-args))
          "macroexpand-check should throw 'did not conform to spec'"))))
