(ns json-parser-benchmark.malli
  (:require [jsonista.core :as j]
            [malli.core :as m]
            [malli.transform :as mt]))

(def mapper j/keyword-keys-object-mapper)

(def github-schema
  [:map
   [:full_name :string]
   [:stargazers_count :int]
   [:open_issues_count :int]
   [:owner [:map [:login :string]]]])

(def twitter-schema
  [:map
   [:statuses
    [:vector
     [:map
      [:id :int]
      [:text :string]
      [:user [:map [:screen_name :string]]]]]]])

(def transformer
  (mt/transformer
   (mt/strip-extra-keys-transformer)
   (mt/json-transformer)))

(def github-coercer (m/coercer github-schema transformer))
(def twitter-coercer (m/coercer twitter-schema transformer))

(defn parse-github [json]
  (github-coercer (j/read-value json mapper)))

(defn parse-twitter [json]
  (twitter-coercer (j/read-value json mapper)))
