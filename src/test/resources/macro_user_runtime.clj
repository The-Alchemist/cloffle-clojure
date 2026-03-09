(do
  (defmacro assert! [pred msg]
    `(when-not ~pred
       (throw (RuntimeException. ~msg))))
  (assert! (> 1 2) "1 is not > 2"))
