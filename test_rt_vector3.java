import clojure.lang.*;
public class test_rt_vector3 {
    public static void main(String[] args) throws Exception {
        RT.init();
        Object form = RT.readString("(def defmacro (fn* defmacro [&form &env name & args] (let [prefix (loop [p (list name) args args] (let [f (first args)] (if (string? f) (recur (cons f p) (next args)) (if (map? f) (recur (cons f p) (next args)) p)))) fdecl (loop [fd args] (if (string? (first fd)) (recur (next fd)) (if (map? (first fd)) (recur (next fd)) fd))) fdecl (if (vector? (first fdecl)) (list fdecl) fdecl) add-implicit-args (fn* [fd] (let [args (first fd)] (cons (clojure.lang.RT/vector (quote &form) (quote &env) args) (next fd)))) add-args (fn* add-args [acc ds] (if (nil? ds) acc (let [d (first ds)] (if (map? d) (conj acc d) (recur (conj acc (add-implicit-args d)) (next ds)))))) fdecl (seq (add-args [] fdecl)) decl (loop [p prefix d fdecl] (if p (recur (next p) (cons (first p) d)) d))] (list (quote do) (cons (quote defn) decl) (list (quote .) (list (quote var) name) (quote (setMacro))) (list (quote var) name)))))");
        Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, form);
        System.out.println("Analyzed list macro creation.");
    }
}
