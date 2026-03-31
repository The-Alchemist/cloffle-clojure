(def cons (fn* cons [x seq] (. clojure.lang.RT (cons x seq))))
(def loop (fn* loop [&form &env & decl] 
  (println "invoking loop macro!") 
  (cons 'loop* decl)))
(. (var loop) (setMacro))

(def sigs (fn* [fdecl] (loop [ret [] fdecls fdecl] ret)))
