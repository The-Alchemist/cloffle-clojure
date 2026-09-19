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

  Use `project` (with or without a schema) and `select`; there is no
  separate full-document parse entry point.

  Does not replace Cheshire or jsonista; call this API explicitly."
  (:import [clojure.lang JsonParser]
           [net.javacrumbs.cloffle.bytecode JsonTypedProjectPlan]))

(defn project
  "Project JSON. With no schema, parse the whole document using JSON-native
  JVM types (integer numbers as long, floats as double, strings as
  java.lang.String). With a schema, the nested shape is the query: only listed
  keys are decoded.

  `schema` is Malli-compatible data or a JSON Schema map subset:

    [:map
     [:id :long]
     [:name {:optional true} :string]
     [:price {:default 0.0} :double]
     [:tags [:vector :string]]
     [:point [:tuple :double :double]]]

    {:type \"object\"
     :required [\"id\"]
     :properties {:id {:type \"integer\"}}}

  Scalar leaves are :int, :long, :double, :boolean, :string, and
  :cloffle/truffle-string. JSON Schema integer maps to long, number to double.
  Use [:maybe leaf] for JSON null. Sparse positional extraction is
  [:cloffle/indexes [0 schema] [3 schema]] or JSON Schema prefixItems.

  Unselected scalars are skipped and are not fully syntax-validated. Duplicate
  selected keys are first-wins; the scan stops once every selected value is
  found. Pass {:cloffle/duplicates :last} for last-wins and a full containing
  scan. :string returns java.lang.String; {:cloffle/strings :truffle} makes
  :string leaves zero-copy UTF-8 TruffleString views. `(hash)` of those views
  uses TruffleString HashCodeNode without toJavaString. Prefer the option
  when a payload carries long or multibyte strings: on the Twitter benchmark
  it cuts allocation from 2,416 to 968 bytes per projection, because :string
  otherwise materializes each value into a java.lang.String. Explicit
  :cloffle/truffle-string is the same for one leaf. Map-entry
  {:cloffle/materialize true} detaches that TruffleString from the request body.

  Constant schemas lower to JsonTypedProject. Dynamic schemas use the same
  interpreted compiler. Malli and JSON Schema are adapters only."
  {:cloffle/lowerable true}
  ([source]
   (JsonParser/parseInput source))
  ([source schema]
   (JsonTypedProjectPlan/project source schema))
  ([source schema opts]
   (JsonTypedProjectPlan/project source schema opts)))

(defn select
  "Read a set of RFC 6901 JSON Pointers out of one document, returning a map
  keyed by the pointer strings:

    (json/select body [\"/data/id\" \"/data/attributes/title\"])
    ;; => {\"/data/id\" \"1\", \"/data/attributes/title\" \"...\"}

  All the pointers resolve in a single scan that stops once every one of them
  is found, unlike JsonNode.at or RapidJSON's Pointer::Get, which walk a
  materialized document once per pointer.

  `~1` decodes to `/` and `~0` to `~`. A numeric token addresses an array
  element or a member of that name, whichever the document holds; `-` and
  leading-zero numerics are member names only. The empty pointer selects the
  whole document.

  Values come back in the same JSON-native types the no-schema `project`
  produces, including maps and vectors when a pointer targets a container.
  Pointers that match nothing are absent from the result. Constant pointer
  vectors lower to JsonTypedProject."
  {:cloffle/lowerable true}
  ([source pointers]
   (JsonTypedProjectPlan/select source pointers))
  ([source pointers opts]
   (JsonTypedProjectPlan/select source pointers opts)))
