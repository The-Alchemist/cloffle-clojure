(def cons (fn* cons [x seq] (. clojure.lang.RT (cons x seq))))
(def let (fn* let [&form &env & decl] (cons 'let* decl)))
(. (var let) (setMacro))
(def loop (fn* loop [&form &env & decl] (cons 'loop* decl)))
(. (var loop) (setMacro))

(def sigs (fn* [fdecl] (let [asig (fn* [fdecl] (first fdecl))] (if (seq? (first fdecl)) (loop [ret [] fdecls fdecl] (if fdecls (recur (conj ret (asig (first fdecls))) (next fdecls)) (seq ret))) (list (asig fdecl))))))
