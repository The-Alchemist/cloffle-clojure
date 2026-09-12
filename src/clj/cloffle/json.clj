;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cloffle.json
  "HTTP-first JSON reader. Keywordized objects become PersistentShapeMap
  or PersistentShapeMap16 (PersistentHashMap past 16 keys). Arrays use
  RT.vector so length <= 8 is PersistentTuple.

  Does not replace Cheshire or jsonista; call this API explicitly."
  (:import [clojure.lang JsonParser]))

(defn- keywordize-key-fn? [kf]
  (or (nil? kf)
      (identical? kf keyword)))

(defn parse-string
  "Parse JSON text. opts may include :key-fn (default `keyword`)."
  ([s]
   (. JsonParser parseString s))
  ([s opts]
   (let [kf (:key-fn opts)]
     (if (keywordize-key-fn? kf)
       (. JsonParser parseString s)
       (. JsonParser parseString s kf)))))

(defn parse-bytes
  "Parse UTF-8 JSON bytes. opts may include :key-fn (default `keyword`)."
  ([b]
   (. JsonParser parseBytes b))
  ([b opts]
   (let [kf (:key-fn opts)]
     (if (keywordize-key-fn? kf)
       (. JsonParser parseBytes b)
       (. JsonParser parseBytes b kf)))))
