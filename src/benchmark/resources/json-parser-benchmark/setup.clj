(def jsonapi "{\"data\":{\"type\":\"articles\",\"id\":\"article-101\",\"attributes\":{\"title\":\"Shape maps in practice\",\"slug\":\"shape-maps\",\"status\":\"published\",\"author\":\"Avery\"},\"relationships\":{\"author\":{\"type\":\"people\",\"id\":\"person-7\"}},\"links\":{\"self\":\"/articles/article-101\"}},\"meta\":{\"request-id\":\"req-101\",\"version\":\"v1\"}}")

(def entity16 "{\"id\":\"user-101\",\"type\":\"user\",\"tenant-id\":\"org-3\",\"email\":\"avery@example.test\",\"username\":\"avery\",\"status\":\"pending\",\"role\":\"admin\",\"created-at\":\"2026-01-10\",\"updated-at\":\"2026-09-09\",\"version\":\"v7\",\"locale\":\"en-US\",\"timezone\":\"America/New_York\",\"profile\":\"x\",\"settings\":\"y\",\"organization\":\"z\",\"audit\":\"w\"}")

(def rows "[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"},{\"id\":3,\"name\":\"c\"},{\"id\":4,\"name\":\"d\"},{\"id\":5,\"name\":\"e\"},{\"id\":6,\"name\":\"f\"},{\"id\":7,\"name\":\"g\"},{\"id\":8,\"name\":\"h\"}]")

(require '[cloffle.json :as json])

(defn guest-parse-lookup-jsonapi []
  (get-in (json/parse-string jsonapi) [:data :attributes :title]))

(defn guest-parse-lookup-entity16 []
  (:email (json/parse-string entity16)))

(defn guest-parse-lookup-rows []
  (:name (nth (json/parse-string rows) 3)))
