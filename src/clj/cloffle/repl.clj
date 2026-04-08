(ns cloffle.repl
  "Guest-driven REPL and script runner: read/print loop in Clojure; each form and whole-file script is
  evaluated via `Context#eval` on injected host `IFn` callbacks (see `install-host-eval!`) so diagnostics match
  the polyglot error path."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [clojure.lang IFn]
           [java.io BufferedReader InputStreamReader]
           [net.javacrumbs.cloffle CloffleRepl]))

(defonce ^:private eval-string-fn-store
  (atom nil))

(defonce ^:private eval-file-fn-store
  (atom nil))

(defn install-host-eval!
  "Called once from `CloffleRepl` bootstrap with host `IFn` values (string+name arity-2, file path arity-1)."
  [^IFn eval-string-fn ^IFn eval-file-fn]
  (reset! eval-string-fn-store eval-string-fn)
  (reset! eval-file-fn-store eval-file-fn)
  nil)

(defn- string-fn! []
  (let [f @eval-string-fn-store]
    (when (nil? f)
      (throw (IllegalStateException. "cloffle.repl host eval-string fn not installed")))
    f))

(defn- file-fn! []
  (let [f @eval-file-fn-store]
    (when (nil? f)
      (throw (IllegalStateException. "cloffle.repl host eval-file fn not installed")))
    f))

(defn- balanced? [^String s]
  (CloffleRepl/isBalanced s))

(defn run-interactive!
  "Line-based REPL on stdin; matches CloffleRepl UX (prompt, multiline, :quit)."
  []
  (let [reader (BufferedReader. (InputStreamReader. System/in))]
    (println "\u001B[1mCloffle REPL\u001B[0m")
    (println "\u001B[2mType an expression, or :quit to exit.\u001B[0m")
    (println)
    (loop [^StringBuilder buf (StringBuilder.) multiline false eval-n 0]
      (print (if multiline "\u001B[2m  .. \u001B[0m" "\u001B[36mcloffle=> \u001B[0m"))
      (flush)
      (if-let [line (.readLine reader)]
        (let [trimmed (str/trim line)]
          (if (and (not multiline) (= ":quit" trimmed))
            nil
            (do
              (.append buf line)
              (.append buf \newline)
              (let [input (str/trim (str buf))]
                (if-not (balanced? input)
                  (recur buf true eval-n)
                  (do
                    (.setLength buf 0)
                    (if-not (str/blank? input)
                      (let [n (inc eval-n)]
                        (.invoke (string-fn!) input (str "repl-" n))
                        (recur buf false n))
                      (recur buf false eval-n))))))))
        nil))))

(defn run-file!
  "Load and run a Clojure file via the polyglot context (full error UX)."
  [^String path]
  (let [f (io/file path)]
    (if-not (.exists f)
      (binding [*out* *err*]
        (println (str "\u001B[31mFile not found: " path "\u001B[0m")))
      (let [name (.getName f)]
        (println (str "\u001B[1m── \u001B[36m" name "\u001B[0m"))
        (.invoke (file-fn!) (.getCanonicalPath f))))))

(defn run-expr!
  "Evaluate a single expression string (inline `-e` / joined args)."
  [^String expr]
  (.invoke (string-fn!) expr "repl"))

(defn run-from-launcher
  "Entry point from `CloffleRepl` bootstrap: `args` are the same as the Java `main` array."
  [& args]
  (clojure.core/in-ns 'user)
  (cond
    (empty? args) (run-interactive!)
    (str/ends-with? (first args) ".clj") (run-file! (first args))
    :else (run-expr! (str/join " " args))))
