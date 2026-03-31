; Small second script for runtime integration tests (independent of bootstrap_slice.clj tail value).
; Last form must evaluate to 7 for BytecodeRuntimeIntegrationTest.

(def extra-n (clojure.lang.Numbers/add 3 4))
extra-n
