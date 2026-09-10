;   Copyright (c) Rich Hickey and Cloffle contributors.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.

(ns clojure.test-clojure.reflector-array-set
  "Regression + generative coverage for clojure.core/aset and aget via RT + :cloffle/op lowering.
   Long loop counters/values must coerce (core.async random-array / alts!).

   Focused run:
     clj -T:build test-array-set-coercion
     clj -T:build test-array-set-coercion :fresh false"
  (:require [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(deftest aset-int-array-long-literal
  (let [a (int-array 3)]
    (aset a 1 2)
    (is (= 2 (aget a 1)))))

(deftest aset-typed-int-with-long-counter
  (let [a (int-array 3)]
    (aset-int a 1 99)
    (is (= 99 (aget a 1)))
    (loop [i 0]
      (when (< i (alength a))
        (aset-int a i i)
        (recur (inc i))))
    (is (= [0 1 2] (vec a)))))

(deftest aset-long-array-and-byte-array
  (let [la (long-array 2)
        ba (byte-array 2)]
    (aset la 0 99)
    (aset ba 1 7)
    (is (= 99 (aget la 0)))
    (is (= 7 (aget ba 1)))))

(deftest aset-object-array-long-index
  (let [a (object-array 2)]
    (aset a 1 :ok)
    (is (= :ok (aget a 1)))))

(deftest aset-multi-dim-int-array
  (let [a (make-array Integer/TYPE 2 3)]
    (aset a 1 2 42)
    (is (= 42 (aget a 1 2)))))

(deftest random-array-style-swap-loop
  (let [a (int-array 4)]
    (loop [i 1]
      (when (< i (alength a))
        (let [j 0]
          (aset a i (aget a j))
          (aset a j i)
          (recur (inc i)))))
    (is (= [3 0 1 2] (vec a)))))

(deftest hof-aset-matches-direct
  (let [a (int-array 8)
        f aset]
    (doseq [i (range 8)]
      (let [v (inc i)]
        (is (= v (f a i v)))
        (is (= v (aget a i)))))))

(deftest raw-array-set-rejects-long-on-int-array
  (is (thrown? IllegalArgumentException
               (java.lang.reflect.Array/set (int-array 1) 0 1))))

(deftest out-of-bounds-aset-throws
  (let [a (int-array 2)]
    (is (thrown? IndexOutOfBoundsException (aset a 2 0)))
    (is (thrown? IndexOutOfBoundsException (aset a -1 0)))))

(deftest with-redefs-aset-disables-lowering
  (let [a (int-array 1)]
    (with-redefs [aset (fn [_ _ _] :redefined)]
      (is (= :redefined (aset a 0 1))))))

(defn- permutation-of-range? [xs]
  (= (range (count xs)) (sort xs)))

(defspec fisher-yates-with-long-indices-is-permutation
  100
  (prop/for-all [n (gen/choose 1 48)]
    (let [a (int-array n)
          rnd (java.util.Random. (long n))]
      (dotimes [k n] (aset a k k))
      (loop [i 1]
        (when (< i n)
          (let [j (.nextInt rnd (inc i))]
            (aset a i (aget a j))
            (aset a j i)
            (recur (inc i)))))
      (permutation-of-range? (vec a)))))

(defspec aset-int-coerces-any-boxed-number-index-and-value
  80
  (prop/for-all [idx (gen/one-of [gen/int
                                  (gen/fmap short gen/byte)
                                  (gen/fmap byte gen/byte)
                                  (gen/double* {:infinite? false :NaN? false :min -1000 :max 1000})])
                 val (gen/one-of [gen/int
                                  (gen/fmap short gen/byte)
                                  (gen/fmap byte gen/byte)
                                  (gen/double* {:infinite? false :NaN? false :min -1e6 :max 1e6})])]
    (let [a (int-array 16)
          i (Math/floorMod (int idx) 16)
          expected (int val)]
      (aset a i val)
      (= expected (aget a i)))))

(defspec aset-long-array-stores-numeric
  50
  (prop/for-all [v (gen/large-integer* {:min -1e15 :max 1e15})]
    (let [a (long-array 1)]
      (aset a 0 v)
      (= (long v) (aget a 0)))))

(defspec object-array-holds-any-ref
  40
  (prop/for-all [v (gen/one-of [gen/keyword gen/string gen/nat (gen/return nil)])]
    (let [a (object-array 1)]
      (aset a 0 v)
      (= v (aget a 0)))))

(def ^:private finite-double
  (gen/double* {:infinite? false :NaN? false :min -1e6 :max 1e6}))

(def ^:private primitive-array-roundtrip-cases
  [[int-array :int gen/int]
   [long-array :long (gen/large-integer* {:min -1000000000000 :max 1000000000000})]
   [byte-array :byte (gen/fmap byte gen/byte)]
   [short-array :short (gen/fmap short gen/int)]
   [float-array :float (gen/fmap float finite-double)]
   [double-array :double finite-double]
   [boolean-array :boolean gen/boolean]
   [char-array :char gen/char]])

(defspec aset-aget-roundtrip-by-array-type
  60
  (gen/bind (gen/elements primitive-array-roundtrip-cases)
            (fn [[ctor coerce val-gen]]
              (prop/for-all [n (gen/choose 1 24)
                             idx gen/int
                             v val-gen]
                (let [a (ctor n)
                      i (mod (int idx) n)
                      stored (case coerce
                               :int (int v)
                               :long (long v)
                               :byte (byte v)
                               :short (short v)
                               :float (float v)
                               :double (double v)
                               :boolean (boolean v)
                               :char (char v))]
                  (aset a i stored)
                  (= stored (aget a i)))))))

(defspec multi-dim-int-array-roundtrip
  40
  (gen/bind (gen/choose 1 6)
            (fn [n]
              (gen/bind (gen/choose 1 6)
                        (fn [m]
                          (prop/for-all [i (gen/choose 0 (dec n))
                                         j (gen/choose 0 (dec m))
                                         v gen/int]
                            (let [a (make-array Integer/TYPE n m)]
                              (aset a i j v)
                              (= v (aget a i j)))))))))

(defspec index-coercion-mod-length
  50
  (prop/for-all [n (gen/choose 2 32)
                 idx (gen/one-of [gen/int
                                  (gen/fmap short gen/int)
                                  (gen/fmap byte gen/byte)
                                  (gen/double* {:infinite? false :NaN? false :min -500 :max 500})])
                 v gen/int]
    (let [a (int-array n)
          i (Math/floorMod (int idx) n)]
      (aset a i v)
      (= (int v) (aget a i)))))
