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

;; Stock inlines these bit operations in the same context. Besides matching their values,
;; direct host calls preserve primitive long results through pipelines into unchecked arithmetic.
(probe "bit/not" (compile-under true '(bit-not 0)))
(probe "bit/and" (compile-under true '(bit-and 15 7 3)))
(probe "bit/or" (compile-under true '(bit-or 8 4 1)))
(probe "bit/xor" (compile-under true '(bit-xor 15 6 3)))
(probe "bit/and-not" (compile-under true '(bit-and-not 15 3 4)))
(probe "bit/shift-left" (compile-under true '(bit-shift-left 3 4)))
(probe "bit/shift-right" (compile-under true '(bit-shift-right -32 3)))
(probe "bit/unsigned-shift-right"
       (compile-under true '(unsigned-bit-shift-right -1 1)))
(probe "bit/arithmetic-pipeline"
       (compile-under true
                      '((fn [^long x]
                          (* (bit-xor (unsigned-bit-shift-right x 30) x) 5))
                        123)))

;; :checked-method ops always rewrite (stock :inline), even with *unchecked-math* false.
(probe "always/bit-xor" (compile-under false '(bit-xor 15 6)))
(probe "always/alength" (compile-under false '(alength (int-array 3))))
(probe "always/unchecked-inc" (compile-under false '(unchecked-inc 0)))
(probe "always/bit-pipeline-default-flag"
       (compile-under false
                      '((fn [^long x]
                          (dec (* (bit-xor (unsigned-bit-shift-right x 30) x) 5)))
                        123)))
(probe "always/zero?" (compile-under false '(zero? 0)))
(probe "always/min-max" (compile-under false '(min 3 1 2)))
(probe "always/quot-rem" (compile-under false '(vector (quot 10 3) (rem 10 3))))
(probe "always/promoting-add" (compile-under false '(+' 1 2 3)))
(probe "always/NaN?" (compile-under false '(NaN? ##NaN)))
(probe "redefs/bit-xor-always-rewrite"
       (compile-under false
                      '(with-redefs [bit-xor (fn [_ _] :redefined)] (bit-xor 1 2))))
(probe "variadic/divide-unchecked" (compile-under true '(/ 8 2 2)))
(probe "variadic/divide-checked-redef"
       (compile-under false
                      '(with-redefs [clojure.core// (fn [& _] :redefined)] (/ 8 2 2))))

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
(probe "cast/long" (compile-under true '(long 1.5)))
(probe "cast/double" (compile-under true '(double 3)))
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
