(ns cloffle.json.malli
  "Malli-compatible frontend for cloffle.json typed projection.

  The supported AST subset is consumed as data and does not require Malli at
  runtime: :map, :vector, :tuple, :maybe, scalar leaf keywords, and map-entry
  :optional/:default properties. Cloffle extensions use :cloffle/* keywords."
  (:require [cloffle.json :as json]))

(defmacro project
  "Project `source` with a constant Malli-compatible schema form."
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
