(ns ^{:doc "Cloffle entrypoint mirroring clojure.main behavior."
       :author "Cloffle contributors"}
  cloffle.main
  (:refer-clojure :exclude [with-bindings])
  (:require [clojure.main :as cm]))

(defn demunge
  "Given a string representation of a fn class, returns a readable version."
  {:added "1.3"}
  [fn-name]
  (cm/demunge fn-name))

(defn root-cause
  "Returns the initial cause of an exception or error by peeling off wrappers."
  {:added "1.3"}
  [t]
  (cm/root-cause t))

(defn stack-element-str
  "Returns a (possibly unmunged) string representation of a StackTraceElement."
  {:added "1.3"}
  [el]
  (cm/stack-element-str el))

(defmacro with-bindings
  "Executes body in the context of thread-local bindings for REPL vars."
  [& body]
  `(cm/with-bindings ~@body))

(defn repl-prompt
  "Default :prompt hook for repl."
  []
  (cm/repl-prompt))

(defn skip-if-eol
  "See clojure.main/skip-if-eol."
  [s]
  (cm/skip-if-eol s))

(defn skip-whitespace
  "See clojure.main/skip-whitespace."
  [s]
  (cm/skip-whitespace s))

(defn renumbering-read
  "See clojure.main/renumbering-read."
  ([opts reader line-number]
   (cm/renumbering-read opts reader line-number)))

(defn repl-read
  "Default :read hook for repl."
  [request-prompt request-exit]
  (cm/repl-read request-prompt request-exit))

(defn repl-exception
  "Returns the root cause of throwables."
  [throwable]
  (cm/repl-exception throwable))

(defn ex-triage
  "Returns analysis of phase, error, cause, and location from Throwable->map."
  [datafied-throwable]
  (cm/ex-triage datafied-throwable))

(defn ex-str
  "Returns a string from an exception triage map."
  [triage-data]
  (cm/ex-str triage-data))

(defn err->msg
  "Helper to return an error message string from an exception."
  [e]
  (cm/err->msg e))

(defn repl-caught
  "Default :caught hook for repl."
  [e]
  (cm/repl-caught e))

(def ^{:doc "A sequence of lib specs that are applied to `require`
by default when a new command-line REPL is started."}
  repl-requires
  cm/repl-requires)

(defmacro with-read-known
  "Evaluates body with *read-eval* set to a known value."
  [& body]
  `(cm/with-read-known ~@body))

(defn repl
  "Generic, reusable read-eval-print loop."
  [& options]
  (apply cm/repl options))

(defn load-script
  "Loads Clojure source from a file or resource given its path."
  [path]
  (cm/load-script path))

(defn legacy-repl
  "Run the legacy REPL compatibility mode."
  [args]
  (#'clojure.main/legacy-repl args))

(defn legacy-script
  "Run the legacy script compatibility mode."
  [args]
  (#'clojure.main/legacy-script args))

(defn report-error
  "Create and output an exception report for a Throwable."
  [t & opts]
  (apply cm/report-error t opts))

(defn main
  "Cloffle equivalent of clojure.main/main."
  [& args]
  (apply cm/main args))
