;; Example Cloffle script for DAP debugging.
;; Run with:  make cloffle-dap FILE=src/script/demos/debug_example.clj
;; Then attach VS Code debugger on port 4711.

(ns debug-example)

(defn greet [name]
  (str "Hello, " name "!"))

(defn factorial [n]
  (if (<= n 1)
    1
    (* n (factorial (dec n)))))

(defn fib [n]
  (cond
    (= n 0) 0
    (= n 1) 1
    :else (+ (fib (- n 1)) (fib (- n 2)))))

(println (greet "Cloffle"))
(println "5! =" (factorial 5))
(println "fib(10) =" (fib 10))
(println "Done!")
