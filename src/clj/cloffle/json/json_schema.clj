(ns cloffle.json.json-schema
  "JSON Schema frontend for cloffle.json typed projection.

  The supported subset is consumed as data and does not require a JSON Schema
  validator at runtime: type object/array/string/boolean/integer/number,
  properties, required, default, items, and prefixItems."
  (:require [cloffle.json :as json]))

(defmacro project
  "Project `source` with a constant JSON Schema map."
  ([source schema]
   `(json/project ~source ~schema))
  ([source schema opts]
   `(json/project ~source ~schema ~opts)))

(defn projector
  "Return an interpreted projector for a schema value assembled dynamically."
  ([schema]
   (fn [source] (json/project source schema)))
  ([schema opts]
   (fn [source] (json/project source schema opts))))
