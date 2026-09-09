(ns test.guest.compile)
(defn compiled-check []
  (com.oracle.truffle.api.CompilerDirectives/inCompiledCode))
