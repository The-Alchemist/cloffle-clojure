;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(def unquote)
(def unquote-splicing)

(def
 ^{:arglists '([& items])
   :doc "Creates a new list containing the items."
   :added "1.0"}
  list (. clojure.lang.PersistentList creator))

(def
 ^{:arglists '([x seq])
    :doc "Returns a new seq where x is the first element and seq is
    the rest."
   :added "1.0"
   :static true}

 cons (fn* ^:static cons [x seq] (. clojure.lang.RT (cons x seq))))

;during bootstrap we don't have destructuring let, loop or fn, will redefine later
(def ^{:macro true} let (fn* let [&form &env & decl] (cons 'let* decl)))

(def ^{:macro true} fn (fn* fn [&form &env & decl] 
         (.withMeta ^clojure.lang.IObj (cons 'fn* decl) 
                    (.meta ^clojure.lang.IMeta &form))))

(def first (fn* ^:static first [coll] (. clojure.lang.RT (first coll))))

(def next (fn* ^:static next [x] (. clojure.lang.RT (next x))))

(def rest (fn* ^:static rest [x] (. clojure.lang.RT (more x))))

(def conj (fn* ^:static conj
        ([] [])
        ([coll] coll)
        ([coll x] (clojure.lang.RT/conj coll x))
        ([coll x & xs]
         (clojure.lang.RT/conj coll x))))

(def second (fn* ^:static second [x] (first (next x))))

(def ffirst (fn* ^:static ffirst [x] (first (first x))))

(def nfirst (fn* ^:static nfirst [x] (next (first x))))

(def fnext (fn* ^:static fnext [x] (first (next x))))

(def nnext (fn* ^:static nnext [x] (next (next x))))

(def seq (fn* ^:static seq ^clojure.lang.ISeq [coll] (. clojure.lang.RT (seq coll))))

(def instance? (fn* instance? [^Class c x] (. c (isInstance x))))

(def seq? (fn* ^:static seq? [x] (instance? clojure.lang.ISeq x)))

(def char? (fn* ^:static char? [x] (instance? Character x)))

(def string? (fn* ^:static string? [x] (instance? String x)))

(def map? (fn* ^:static map? [x] (instance? clojure.lang.IPersistentMap x)))

(def vector? (fn* ^:static vector? [x] (instance? clojure.lang.IPersistentVector x)))

;; ORIGINAL TESTS
(def a 10)
(def b 20)
(if a b :false)

(def add (fn* [x y] (clojure.core/+ x y)))
(add 10 20)

(def my-vec [1 2 3])
(def my-map {:a 1 :b 2})
(def my-set #{1 2 3})

my-vec
my-map
my-set

(def my-str (java.lang.String. "Hello World"))
(.length my-str)
(java.lang.Math/abs -100)
(instance? java.lang.String my-str)

(try
  (clojure.core// 1 0)
  (catch ArithmeticException e
    "Caught exception!"))

(try
  (throw (java.lang.RuntimeException. "Oops!"))
  (catch RuntimeException e
    "Caught runtime exception!"))

(meta ^:foo [1 2 3])

(def multi-fn (fn* ([] 0) ([x] 1)))

(def meta (fn* ^:static meta [x]
        (if (instance? clojure.lang.IMeta x)
          (. ^clojure.lang.IMeta x (meta)))))

(def with-meta (fn* ^:static with-meta [^clojure.lang.IObj x m]
             (. x (withMeta m))))

(def last (fn* ^:static last [s]
            (if (next s)
              (recur (next s))
              (first s))))

(def butlast (fn* ^:static butlast [s]
           (loop [ret [] s s]
             (if (next s)
               (recur (conj ret (first s)) (next s))
               (seq ret)))))

(def defn (fn* defn [&form &env name & fdecl]
        (let [m (if (string? (first fdecl))
                  {:doc (first fdecl)}
                  {})
              fdecl (if (string? (first fdecl))
                      (next fdecl)
                      fdecl)
              m (if (map? (first fdecl))
                  (conj m (first fdecl))
                  m)
              fdecl (if (map? (first fdecl))
                      (next fdecl)
                      fdecl)
              fdecl (if (vector? (first fdecl))
                      (list fdecl)
                      fdecl)
              m (if (map? (last fdecl))
                  (conj m (last fdecl))
                  m)
              fdecl (if (map? (last fdecl))
                      (butlast fdecl)
                      fdecl)
              m (conj (if (meta name) (meta name) {}) m)]
          (list 'def (with-meta name m)
                (with-meta (cons 'fn* fdecl) {:rettag (:tag m)})))))

(. (var defn) (setMacro))
