(def a 10)
(def b 20)
(if a b :false)

(def add (fn [x y] (clojure.core/+ x y)))
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

