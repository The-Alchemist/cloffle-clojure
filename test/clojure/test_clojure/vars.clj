;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

; Author: Frantisek Sodomka, Stephen C. Gilardi


(ns clojure.test-clojure.vars
  (:use clojure.test)
  (:import [clojure.lang Var Namespace Symbol]))

; http://clojure.org/vars

; def
; defn defn- defonce

; declare intern binding find-var var

(def ^:dynamic a)
(deftest test-binding
  (are [x y] (= x y)
      (eval `(binding [a 4] a)) 4     ; regression in Clojure SVN r1370
  ))

; var-get var-set alter-var-root [var? (predicates.clj)]
; with-in-str with-out-str
; with-open

(deftest test-with-local-vars
  (let [factorial (fn [x]
                    (with-local-vars [acc 1, cnt x]
                      (while (> @cnt 0)
                        (var-set acc (* @acc @cnt))
                        (var-set cnt (dec @cnt)))
                      @acc))]
    (is (= (factorial 5) 120))))

(deftest test-with-precision
  (are [x y] (= x y)
       (with-precision 4 (+ 3.5555555M 1)) 4.556M
       (with-precision 6 (+ 3.5555555M 1)) 4.55556M
       (with-precision 6 :rounding CEILING     (+ 3.5555555M 1)) 4.55556M
       (with-precision 6 :rounding FLOOR       (+ 3.5555555M 1)) 4.55555M
       (with-precision 6 :rounding HALF_UP     (+ 3.5555555M 1)) 4.55556M
       (with-precision 6 :rounding HALF_DOWN   (+ 3.5555555M 1)) 4.55556M
       (with-precision 6 :rounding HALF_EVEN   (+ 3.5555555M 1)) 4.55556M
       (with-precision 6 :rounding UP          (+ 3.5555555M 1)) 4.55556M
       (with-precision 6 :rounding DOWN        (+ 3.5555555M 1)) 4.55555M
       (with-precision 6 :rounding UNNECESSARY (+ 3.5555M 1))    4.5555M))

(deftest test-settable-math-context
  (is (=
       (clojure.main/with-bindings
         (set! *math-context* (java.math.MathContext. 8))
         (+ 3.55555555555555M 1))
       4.5555556M)))

; set-validator get-validator

; doc find-doc test

(def stub-me :original)

(deftest test-with-redefs-fn
  (let [p (promise)]
    (with-redefs-fn {#'stub-me :temp}
      (fn []
        (.start (Thread. #(deliver p stub-me)))
        @p))
    (is (= :temp @p))
    (is (= :original stub-me))))

(deftest test-with-redefs
  (let [p (promise)]
    (with-redefs [stub-me :temp]
      (.start (Thread. #(deliver p stub-me)))
      @p)
    (is (= :temp @p))
    (is (= :original stub-me))))

(deftest test-with-redefs-throw
  (let [p (promise)]
    (is (thrown? Exception
      (with-redefs [stub-me :temp]
        (deliver p stub-me)
        (throw (Exception. "simulated failure in with-redefs")))))
    (is (= :temp @p))
    (is (= :original stub-me))))

(def ^:dynamic dynamic-var 1)

(deftest test-with-redefs-inside-binding
  (binding [dynamic-var 2]
    (is (= 2 dynamic-var))
    (with-redefs [dynamic-var 3]
      (is (= 2 dynamic-var))))
  (is (= 1 dynamic-var)))

;; Cloffle: with-redefs-fn teardown must not call seq/first/next/nth through Vars,
;; or mocking any of them aborts the finally and permanently poisons the root.
(defn- assert-with-redefs-restores
  [^clojure.lang.Var v call]
  (let [original (.getRawRoot v)
        mock (fn [& _] :redefined)]
    (try
      (is (= :redefined (with-redefs-fn {v mock} call)))
      (is (identical? original (.getRawRoot v)))
      (finally
        ;; Host-only cleanup so a regression cannot poison later tests in this JVM.
        (.bindRoot v original)))))

(deftest test-with-redefs-restores-nth
  (assert-with-redefs-restores #'clojure.core/nth
                               (fn [] (nth [:a :b :c] 0))))

(deftest test-with-redefs-restores-first
  (assert-with-redefs-restores #'clojure.core/first
                               (fn [] (first [:a :b :c]))))

(deftest test-with-redefs-restores-seq
  (assert-with-redefs-restores #'clojure.core/seq
                               (fn [] (seq [:a :b :c]))))

(deftest test-with-redefs-restores-next
  (assert-with-redefs-restores #'clojure.core/next
                               (fn [] (next [:a :b :c]))))

(defn sample [& args]
  0)

(defn- disposable-var
  ([root] (disposable-var root false))
  ([root dynamic?]
   (let [ns (Namespace/findOrCreate (Symbol/intern nil (str "test.var.audit." (System/nanoTime))))
         v (Var/intern ns (Symbol/intern nil "x") root)]
     (when dynamic?
       (.setDynamic v))
     v)))

(defn- core-seq-vars
  []
  (doto (java.util.ArrayList.)
    (.add #'clojure.core/seq)
    (.add #'clojure.core/first)
    (.add #'clojure.core/next)
    (.add #'clojure.core/nth)))

(deftest test-alter-var-root-args-and-return
  (let [v (disposable-var 10)
        seen (atom nil)
        ret (alter-var-root v (fn [old a b]
                                (reset! seen [old a b])
                                (+ old a b))
                            1 5)]
    (is (= 16 ret))
    (is (= 16 @v))
    (is (= [10 1 5] @seen))))

(deftest test-alter-var-root-throw-leaves-raw-root
  (let [root (Object.)
        v (disposable-var root)]
    (is (thrown? Exception
                 (alter-var-root v (fn [_] (throw (Exception. "nope"))))))
    (is (identical? root (.getRawRoot v)))))

(deftest test-alter-var-root-validator-rejects-without-watch
  (let [v (disposable-var 0)
        fires (atom 0)]
    (.setValidator v even?)
    (add-watch v :w (fn [& _] (swap! fires inc)))
    (is (thrown? Exception (alter-var-root v (constantly 1))))
    (is (= 0 (.getRawRoot v)))
    (is (zero? @fires))))

(deftest test-alter-var-root-watch-once
  (let [v (disposable-var :old)
        seen (atom nil)
        ret (do
              (add-watch v :w (fn [_ _ o n] (reset! seen [o n])))
              (alter-var-root v (constantly :new)))]
    (is (= :new ret))
    (is (= [:old :new] @seen))
    (is (= :new (.getRawRoot v)))))

(deftest test-alter-var-root-while-thread-bound
  (let [v (disposable-var :root true)]
    (push-thread-bindings {v :bound})
    (try
      (alter-var-root v (constantly :new-root))
      (is (= :bound @v))
      (is (= :new-root (.getRawRoot v)))
      (finally
        (Var/popThreadBindings)))
    (is (= :new-root @v))))

(deftest test-alter-var-root-concurrent-increments
  (let [v (disposable-var 0)
        n 100
        threads 8
        latch (java.util.concurrent.CountDownLatch. threads)
        start (java.util.concurrent.CountDownLatch. 1)
        err (atom nil)]
    (dotimes [_ threads]
      (.start (Thread. (fn []
                         (.await start)
                         (try
                           (dotimes [_ n]
                             (alter-var-root v inc))
                           (catch Throwable t
                             (reset! err t))
                           (finally
                             (.countDown latch)))))))
    (.countDown start)
    (.await latch)
    (is (nil? @err))
    (is (= (* n threads) @v))))

(deftest test-alter-var-root-under-seq-first-next-nth-redef
  (let [it (.iterator (core-seq-vars))]
    (while (.hasNext it)
      (let [cv ^Var (.next it)
            target (disposable-var 0)
            orig (.getRawRoot cv)
            result (atom nil)
            err (atom nil)]
        (try
          (.bindRoot cv (fn [& _] :redefined))
          (try
            (reset! result (alter-var-root target inc))
            (catch Throwable t
              (reset! err t)))
          (finally
            (.bindRoot cv orig)))
        (is (nil? @err) (str (.sym cv)))
        (is (= 1 @result))
        (is (= 1 @target))
        (is (identical? orig (.getRawRoot cv)))))))

(deftest test-push-pop-thread-bindings-restores
  (let [v (disposable-var :root true)
        before (get-thread-bindings)]
    (push-thread-bindings {v :bound})
    (try
      (is (= :bound @v))
      (is (= :bound (get (get-thread-bindings) v)))
      (finally
        (Var/popThreadBindings)))
    (is (= :root @v))
    (is (= before (get-thread-bindings)))))

(deftest test-nested-thread-bindings-lifo
  (let [v (disposable-var :root true)]
    (push-thread-bindings {v :a})
    (try
      (is (= :a @v))
      (push-thread-bindings {v :b})
      (try
        (is (= :b @v))
        (finally
          (Var/popThreadBindings)))
      (is (= :a @v))
      (finally
        (Var/popThreadBindings)))
    (is (= :root @v))))

(deftest test-parallel-thread-bindings
  (let [a (disposable-var :ra true)
        b (disposable-var :rb true)]
    (push-thread-bindings {a :a b :b})
    (try
      (is (= :a @a))
      (is (= :b @b))
      (finally
        (Var/popThreadBindings)))
    (is (= :ra @a))
    (is (= :rb @b))))

(deftest test-binding-exception-unwinds-frame
  (let [v (disposable-var :root true)]
    (is (thrown? Exception
                 (push-thread-bindings {v :bound})
                 (try
                   (throw (Exception. "boom"))
                   (finally
                     (Var/popThreadBindings)))))
    (is (= :root @v))))

(deftest test-with-bindings-star-exception-unwinds-frame
  (let [v (disposable-var :root true)]
    (is (thrown? Exception
                 (with-bindings* {v :bound}
                   (fn [] (throw (Exception. "boom"))))))
    (is (= :root @v))))

(deftest test-raw-child-thread-does-not-inherit-binding
  (let [v (disposable-var :root true)
        p (java.util.concurrent.CompletableFuture.)]
    (push-thread-bindings {v :bound})
    (try
      (.start (Thread. (fn [] (.complete p @v))))
      (is (= :root (.get p 5 java.util.concurrent.TimeUnit/SECONDS)))
      (finally
        (Var/popThreadBindings)))))

(deftest test-bound-fn-and-future-convey-binding
  (let [v (disposable-var :root true)
        f (do
            (push-thread-bindings {v :bound})
            (try
              (bound-fn* (fn [] @v))
              (finally
                (Var/popThreadBindings))))
        fut (do
              (push-thread-bindings {v :bound})
              (try
                (future @v)
                (finally
                  (Var/popThreadBindings))))]
    (is (= :bound (f)))
    (is (= :bound (deref fut 5000 :timeout)))))

(deftest test-bind-non-dynamic-throws-without-frame-change
  (let [v (disposable-var :root false)
        before (get-thread-bindings)]
    (is (thrown? IllegalStateException (push-thread-bindings {v :x})))
    (is (= :root @v))
    (is (= before (get-thread-bindings)))))

(deftest test-unmatched-pop-on-fresh-thread
  (let [p (java.util.concurrent.CompletableFuture.)]
    (doto (Thread.
           (fn []
             (let [threw (try
                           (Var/popThreadBindings)
                           false
                           (catch IllegalStateException _ true))
                   v (disposable-var :root true)]
               (push-thread-bindings {v :bound})
               (try
                 (.complete p (and threw (= :bound @v)))
                 (finally
                   (Var/popThreadBindings))))))
      .start
      .join)
    (is (true? (.get p 5 java.util.concurrent.TimeUnit/SECONDS)))))

(deftest test-thread-bindings-under-seq-first-next-nth-redef
  (let [it (.iterator (core-seq-vars))]
    (while (.hasNext it)
      (let [cv ^Var (.next it)
            v (disposable-var :root true)
            orig (.getRawRoot cv)
            during (atom nil)
            after (atom nil)
            err (atom nil)]
        (try
          (.bindRoot cv (fn [& _] :redefined))
          (try
            (push-thread-bindings {v :bound})
            (try
              (reset! during @v)
              (finally
                (Var/popThreadBindings)))
            (reset! after @v)
            (catch Throwable t
              (reset! err t)))
          (finally
            (.bindRoot cv orig)))
        (is (nil? @err) (str (.sym cv)))
        (is (= :bound @during))
        (is (= :root @after))
        (is (identical? orig (.getRawRoot cv)))))))

(deftest test-vars-apply-lazily
  (is (= 0 (deref (future (apply sample (range)))
                  1000 :timeout)))
  (is (= 0 (deref (future (apply #'sample (range)))
                  1000 :timeout))))
