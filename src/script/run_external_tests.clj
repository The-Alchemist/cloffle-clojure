(ns run-external-tests
  (:require [clojure.test :as t]))

(let [namespaces (map symbol *command-line-args*)]
  (println "Running tests for:" namespaces)
  (doseq [ns namespaces]
    (require ns))
  (let [results (apply t/run-tests namespaces)]
    (if (and (zero? (:fail results)) (zero? (:error results)))
      (System/exit 0)
      (System/exit 1))))
