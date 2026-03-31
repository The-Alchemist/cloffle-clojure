(ns user)
(def foo 10)
(def bar (let* [x foo] x))
(println bar)
