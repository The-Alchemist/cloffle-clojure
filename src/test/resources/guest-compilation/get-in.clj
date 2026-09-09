(ns test.guest.get-in)
(defn nested []
  [(get-in {:user {:profile {:name "Alice"}}} [:user :profile :name])
   (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)])
