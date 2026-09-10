;; Differential probe for :cloffle/unchecked-op call-site rewriting.
;; The build task runs this exact file under stock Clojure 1.12 and Cloffle
;; and compares every key/value record.

(defn- emit
  [^String k v]
  (.println System/out (.concat (.concat k "\t") (pr-str v))))

(defmacro probe
  [k & body]
  `(try
     (emit ~k (do ~@body))
     (catch Throwable _#
       (emit ~k :threw))))

(defn- compile-under
  [flag form]
  (binding [*unchecked-math* flag]
    (eval form)))

;; Checked/unchecked gate and each arithmetic operation.
(probe "checked/add"
       (compile-under false
                      '(try (+ Long/MAX_VALUE 1)
                            (catch ArithmeticException _ :threw))))
(probe "unchecked/add" (compile-under true '(+ Long/MAX_VALUE 1)))
(probe "warn-on-boxed/add" (compile-under :warn-on-boxed '(+ Long/MAX_VALUE 1)))
(probe "unchecked/subtract" (compile-under true '(- Long/MIN_VALUE 1)))
(probe "unchecked/multiply" (compile-under true '(* Long/MAX_VALUE 3)))
(probe "unchecked/inc" (compile-under true '(inc Long/MAX_VALUE)))
(probe "unchecked/dec" (compile-under true '(dec Long/MIN_VALUE)))

;; Variadic arithmetic must fold left pairwise.
(probe "variadic/add" (compile-under true '(+ Long/MAX_VALUE 1 1)))
(probe "variadic/multiply" (compile-under true '(* Long/MAX_VALUE 3 5)))
(probe "variadic/subtract" (compile-under true '(- Long/MIN_VALUE 1 1)))

;; Arity gates: +/* identities and unary values remain ordinary Var calls;
;; unary - is a real unchecked operation.
(probe "arity/add-zero" (compile-under true '(+)))
(probe "arity/add-one" (compile-under true '(+ 7)))
(probe "arity/multiply-zero" (compile-under true '(*)))
(probe "arity/multiply-one" (compile-under true '(* 7)))
(probe "arity/minus-one" (compile-under true '(- Long/MIN_VALUE)))

;; A local binding with the same symbol must never be mistaken for the core Var.
(probe "scope/local-shadow"
       (compile-under true '(let [+ (fn [_ _] :local)] (+ 1 2))))
(probe "scope/qualified-core"
       (compile-under true '(clojure.core/+ Long/MAX_VALUE 1)))

;; Match upstream inline semantics: a rewritten call bypasses a later with-redefs,
;; while the ordinary checked call still observes it.
(probe "redefs/checked"
       (compile-under false
                      '(with-redefs [+ (fn [_ _] :redefined)] (+ 1 2))))
(probe "redefs/unchecked"
       (compile-under true
                      '(with-redefs [+ (fn [_ _] :redefined)] (+ 1 2))))

;; Every unchecked cast named by core metadata. Values are chosen so the checked
;; counterpart rejects them and the unchecked operation has a stable result.
(probe "cast/int" (compile-under true '(int 4294967296)))
(probe "cast/byte" (compile-under true '(byte 128)))
(probe "cast/short" (compile-under true '(short 65536)))
(probe "cast/char" (compile-under true '(int (char 65536))))
(probe "cast/float" (compile-under true '(Float/isInfinite (float 1.0e40))))
(probe "cast/int-checked"
       (compile-under false
                      '(try (int 4294967296)
                            (catch Throwable _ :threw))))
(probe "cast/byte-checked"
       (compile-under false
                      '(try (byte 128)
                            (catch Throwable _ :threw))))
(probe "cast/short-checked"
       (compile-under false
                      '(try (short 65536)
                            (catch Throwable _ :threw))))
(probe "cast/char-checked"
       (compile-under false
                      '(try (char 65536)
                            (catch Throwable _ :threw))))
(probe "cast/float-checked"
       (compile-under false
                      '(try (float 1.0e40)
                            (catch Throwable _ :threw))))

;; Directly pin the original blind spot: deftype methods are emitted by
;; Compiler.java's ASM path and never reach ExprToBytecode :cloffle/op lowering.
(definterface IUncheckedMathProbe
  (plus [y]))

(binding [*unchecked-math* true]
  (eval
   '(deftype UncheckedMathProbe [x]
      IUncheckedMathProbe
      (plus [_ y] (+ x y)))))

(probe "asm/deftype"
       (eval '(.plus (UncheckedMathProbe. Long/MAX_VALUE) 1)))
