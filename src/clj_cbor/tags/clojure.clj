(ns clj-cbor.tags.clojure
  "Built-in tag support for the clojure type extensions.

  See:
  - https://github.com/lucas-clemente/cbor-specs/blob/master/id.md"
  (:require
    [clj-cbor.data.model :as data])
  (:import
    (clojure.lang
      Keyword
      Symbol
      TaggedLiteral)))


;; ## Symbols & Keywords

(defn format-symbol
  [value]
  (data/tagged-value 39 (str value)))


(defn parse-symbol
  [tag value]
  (when-not (string? value)
    (throw (ex-info (str "Symbols must be tagged strings, got: "
                         (class value))
                    {:tag tag, :value value})))
  (if (= \: (first value))
    (keyword (subs value 1))
    (symbol value)))



;; ## Tagged Literals

;; Tag 27
;; http://cbor.schmorp.de/generic-object

(defn format-tagged-literal
  [value]
  (data/tagged-value 27 [(str (:tag value)) (:form value)]))


(defn parse-tagged-literal
  [tag value]
  (when-not (and (sequential? value) (= 2 (count value)))
    (throw (ex-info (str "Sets must be tagged two-element arrays, got: "
                         (class value))
                    {:tag tag, :value value})))
  (tagged-literal (symbol (first value)) (second value)))



;; ## Codec Formatter/Handler Maps

(def clojure-write-handlers
  "Map of Clojure types to write handler functions."
  {Keyword       format-symbol
   Symbol        format-symbol
   TaggedLiteral format-tagged-literal})


(def clojure-read-handlers
  "Map of tag codes to read handlers to parse Clojure values."
  {27 parse-tagged-literal
   39 parse-symbol})
