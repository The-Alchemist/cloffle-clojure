(ns clojure.polyglot.error
  "Polyglot / JVM embedding helpers: triage-shaped maps and messages."
  (:import net.javacrumbs.cloffle.PolyglotErrorTriage))

(defn- guest-line [fr]
  (str "\n    "
       (or (:source fr) "?") ":"
       (or (:line fr) "?") ":"
       (or (:column fr) "?")
       (when-let [r (:root-name fr)]
         (str "  " (char 40) r (char 41)))
       (when-let [s (:snippet fr)]
         (str "  " s))))

(defn triage-ex-str
  "Like clojure.main/ex-str for triage maps, plus macro-stack and guest-frames appendices."
  [triage]
  (when triage
    (let [base (clojure.main/ex-str triage)
          mac (when-let [ms (:clojure.error/macro-stack triage)]
                (when (seq ms)
                  (str "\n  Macro stack: " (pr-str ms) "\n")))
          gfs (:clojure.error/guest-frames triage)
          guest (when (seq gfs)
                  (str "\n  Guest frames:"
                       (clojure.string/join "" (map guest-line gfs))
                       "\n"))]
      (str base mac guest))))

(defn polyglot-exception-message
  "Full message string for a PolyglotException (triage + triage-ex-str)."
  [^org.graalvm.polyglot.PolyglotException e]
  (-> (PolyglotErrorTriage/triage e)
      triage-ex-str))
