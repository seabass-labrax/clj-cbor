(ns clj-cbor.tags.numbers
  "Built-in tag support for the number extensions in RFC 7049. See section
  2.4.2."
  (:require
    [clj-cbor.data.core :as data])
  (:import
    (clojure.lang
      BigInt
      Ratio)
    (java.math
      BigDecimal
      BigInteger)))


;; ## Bignums

;; Tag 2, 3
;; RFC 7049 Section 2.4.2

(def ^:const positive-bignum-tag 2)
(def ^:const negative-bignum-tag 3)


(defn format-bignum
  [value]
  (let [big-integer (biginteger value)]
    (if-not (neg? big-integer)
      (data/tagged-value
        positive-bignum-tag
        (.toByteArray big-integer))
      (data/tagged-value
        negative-bignum-tag
        (-> big-integer
            (.add BigInteger/ONE)
            (.negate)
            (.toByteArray))))))


(defn parse-positive-bignum
  [value]
  (when-not (data/bytes? value)
    (throw (ex-info (str "Bignums must be represented as a tagged byte string, got: "
                         (class value))
                    {:value value})))
  (bigint (BigInteger. ^bytes value)))


(defn parse-negative-bignum
  [value]
  (when-not (data/bytes? value)
    (throw (ex-info (str "Bignums must be represented as a tagged byte string, got: "
                         (class value))
                    {:value value})))
  (-> (BigInteger. ^bytes value)
      (.add BigInteger/ONE)
      (.negate)
      (bigint)))



;; ## Decimal Fractions

;; Tag 4
;; RFC 7049 Section 2.4.3

(def ^:const big-decimal-tag 4)


(defn format-big-decimal
  [^BigDecimal value]
  (let [exponent (.scale value)
        mantissa (.unscaledValue value)]
    (data/tagged-value big-decimal-tag [(- exponent) mantissa])))


(defn parse-big-decimal
  [value]
  (when-not (and (sequential? value) (= 2 (count value)))
    (throw (ex-info (str "Decimal fractions must be represented with a two-element array, got: "
                         (pr-str value))
                    {:value value})))
  (let [[exponent mantissa] value]
    (BigDecimal. (biginteger mantissa) (int (- exponent)))))



;; ## Bigfloats

;; Tag 5
;; RFC 7049 Section 2.4.3

;; Not Supported



;; ## Ratios

;; Tag 30
;; http://peteroupc.github.io/CBOR/rational.html

(def ^:const ratio-tag 30)


(defn format-ratio
  [value]
  (data/tagged-value ratio-tag [(numerator value) (denominator value)]))


(defn parse-ratio
  [value]
  (when-not (and (sequential? value) (= 2 (count value)))
    (throw (ex-info (str "Rational numbers must be represented with a two-element array, got: "
                         (pr-str value))
                    {:value value})))
  (let [[numerator denominator] value]
    (Ratio. (biginteger numerator) (biginteger denominator))))



;; ## Codec Formatter/Handler Maps

(def number-write-handlers
  "Map of number types to write handler functions."
  {BigInt     format-bignum
   BigInteger format-bignum
   BigDecimal format-big-decimal
   Ratio      format-ratio})


(def number-read-handlers
  "Map of tag codes to read handlers to parse number values."
  {positive-bignum-tag parse-positive-bignum
   negative-bignum-tag parse-negative-bignum
   big-decimal-tag     parse-big-decimal
   ratio-tag           parse-ratio})
