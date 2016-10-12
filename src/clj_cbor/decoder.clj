(ns clj-cbor.decoder
  (:require
    [clj-cbor.data :as data]
    [clj-cbor.data.float16 :as float16]
    [clojure.string :as str])
  (:import
    (java.io
      ByteArrayOutputStream
      DataInputStream
      EOFException
      InputStream)))


;; ## Decoder Protocol

(defprotocol Decoder

  (read-value*
    [decoder input header]
    "Reads a single value from the `DataInputStream`, given the just-read
    initial byte.")

  (unknown-tag
    [decoder tag value]
    "Return a representation for an unknown tagged value.")

  (unknown-simple
    [decoder value]
    "Return a representation for an unknown simple value."))


(defn read-value
  "Reads a single value from the `DataInputStream`."
  [decoder ^DataInputStream input]
  (read-value* decoder input (.readUnsignedByte input)))



;; ## Error Handling

(defn decoder-exception!
  "Default behavior for decoding errors."
  [error-type message]
  (throw (ex-info (str "Decoding failure: " message)
                  {:error error-type})))


(def ^:dynamic *error-handler*
  "Dynamic error handler which can be bound to a function which will be called
  with a type keyword and a message."
  decoder-exception!)



;; ## Reader Functions

(defn- decode-header
  "Determines the major type keyword and additional information encoded by the
  header byte. §2.1"
  [header]
  [(-> header
       (bit-and 0xE0)
       (bit-shift-right 5)
       (bit-and 0x07)
       (data/major-types))
   (bit-and header 0x1F)])


(defn- read-bytes
  "Reads `length` bytes from the input stream and returns them as a byte
  array."
  ^bytes
  [^DataInputStream input length]
  (let [buffer (byte-array length)]
    (.readFully input buffer)
    buffer))


(defn- read-unsigned-long
  "Reads an unsigned long value from the input stream. If the value overflows
  into the negative, it is promoted to a bigint."
  [^DataInputStream input]
  (let [value (.readLong input)]
    (if (neg? value)
      ; Overflow, promote to BigInt.
      (->>
        [(bit-and 0xFF (bit-shift-right value  0))
         (bit-and 0xFF (bit-shift-right value  8))
         (bit-and 0xFF (bit-shift-right value 16))
         (bit-and 0xFF (bit-shift-right value 24))
         (bit-and 0xFF (bit-shift-right value 32))
         (bit-and 0xFF (bit-shift-right value 40))
         (bit-and 0xFF (bit-shift-right value 48))
         (bit-and 0xFF (bit-shift-right value 56))]
        (byte-array)
        (java.math.BigInteger. 1)
        (bigint))
      ; Value fits in a long, return directly.
      value)))


(defn- read-int
  "Reads a size integer from the initial bytes of the input stream."
  [^DataInputStream input ^long info]
  (if (< info 24)
    ; Info codes less than 24 directly represent the number.
    info
    ; Otherwise, signify the number of bytes following.
    (case info
      24 (.readUnsignedByte input)
      25 (.readUnsignedShort input)
      26 (bit-and (.readInt input) 0xFFFFFFFF)
      27 (read-unsigned-long input)
      (28 29 30)
        (*error-handler*
          ::reserved-length
          (format "Additional information int code %d is reserved."
                  info))
      31 :indefinite)))


(defn- read-chunks
  "Reads chunks from the input in a streaming fashion, combining them with the
  given reducing function. All chunks must have the given major type and
  definite length."
  [decoder ^DataInputStream input chunk-type reducer]
  (loop [state (reducer)]
    (let [header (.readUnsignedByte input)]
      (if (== header data/break)
        ; Break code, finish up result.
        (reducer state)
        ; Read next value.
        (let [[mtype info] (decode-header header)]
          (cond
            ; Illegal element type.
            (not= chunk-type mtype)
              (*error-handler*
                ::illegal-chunk
                (str chunk-type " stream may not contain chunks of type " mtype))

            ; Illegal indefinite-length chunk.
            (= info 31)
              (*error-handler*
                ::definite-length-required
                (str chunk-type " stream chunks must have a definite length"))

            ; Reduce state with next value.
            :else
              (recur (reducer state (read-value* decoder input header)))))))))


(defn- read-value-stream
  "Reads values from the input in a streaming fashion, combining them with the
  given reducing function."
  [decoder ^DataInputStream input reducer]
  (loop [state (reducer)]
    (let [header (.readUnsignedByte input)]
      (if (== header data/break)
        ; Break code, finish up result.
        (reducer state)
        ; Read next value.
        (recur (reducer state (read-value* decoder input header)))))))



;; ## Major Types

(defn- read-positive-integer
  "Reads an unsigned integer from the input stream."
  [_ ^DataInputStream input info]
  (let [value (read-int input info)]
    (if (= :indefinite value)
      (*error-handler*
        ::definite-length-required
        "Encoded integers cannot have indefinite length.")
      value)))


(defn- read-negative-integer
  "Reads a negative integer from the input stream."
  [decoder input info]
  (- -1 (read-positive-integer decoder input info)))


(defn- concat-bytes
  "Reducing function which builds a contiguous byte-array from a sequence of
  byte-array chunks."
  ([]
   (ByteArrayOutputStream.))
  ([buffer]
   (.toByteArray ^ByteArrayOutputStream buffer))
  ([buffer v]
   (.write ^ByteArrayOutputStream buffer ^bytes v)
   buffer))


(defn- read-byte-string
  "Reads a sequence of bytes from the input stream."
  [decoder ^DataInputStream input info]
  (let [length (read-int input info)]
    (if (= length :indefinite)
      ; Read sequence of definite-length byte strings.
      (read-chunks decoder input :byte-string concat-bytes)
      ; Read definite-length byte string.
      (read-bytes input length))))


(defn- concat-text
  "Reducing function which builds a contiguous string from a sequence of string
  chunks."
  ([]
   (StringBuilder.))
  ([buffer]
   (str buffer))
  ([buffer v]
   (.append ^StringBuilder buffer ^String v)
   buffer))


(defn- read-text-string
  "Reads a sequence of bytes from the input stream."
  [decoder ^DataInputStream input info]
  (let [length (read-int input info)]
    (if (= length :indefinite)
      ; Read sequence of definite-length text strings.
      (read-chunks decoder input :text-string concat-text)
      ; Read definite-length text string.
      (String. (read-bytes input length) "UTF-8"))))


(defn- build-array
  "Reducing function which builds a vector to represent a data array."
  ([] [])
  ([xs] xs)
  ([xs v] (conj xs v)))


(defn- read-array
  "Reads an array of items from the input stream."
  [decoder ^DataInputStream input info]
  (let [length (read-int input info)]
    (if (= length :indefinite)
      ; Read streaming sequence of elements.
      (read-value-stream decoder input build-array)
      ; Read `length` elements.
      (->>
        (repeatedly #(read-value decoder input))
        (take length)
        (vec)))))


(defn- build-map
  "Reducing function which builds a map from a sequence of alternating key and
  value elements."
  ([]
   [{}])
  ([[m k :as state]]
   (if (= 1 (count state))
     m
     (*error-handler*
       ::missing-map-value
       (str "Streaming map did not contain a value for key: "
            (pr-str k)))))
  ([[m k :as state] e]
   (if (= 1 (count state))
     (if (contains? m e)
       ; Duplicate key error.
       (*error-handler*
         ::duplicate-map-key
         (str "Streaming map contains duplicate key: "
              (pr-str e)))
       ; Save key and wait for value.
       [m e])
     ; Add completed entry to map.
     [(assoc m k e)])))


(defn- read-map
  [decoder ^DataInputStream input info]
  (let [length (read-int input info)]
    (if (= length :indefinite)
      ; Read streaming sequence of key/value entries.
      (read-value-stream decoder input build-map)
      ; Read `length` entry pairs.
      (->>
        (repeatedly #(read-value decoder input))
        (take (* 2 length))
        (transduce identity build-map)))))


(defn- read-tagged
  [decoder ^DataInputStream input info]
  (let [tag (read-int input info)
        value (read-value decoder input)
        handler (get-in decoder [:tag-handlers tag] unknown-tag)]
    (handler decoder tag value)))


(defn- read-simple
  "Reads a simple value from the input."
  [decoder ^DataInputStream input ^long info]
  (case info
    20 false
    21 true
    22 nil
    23 data/undefined
    24 (unknown-simple decoder (.readUnsignedByte input))
    25 (float16/from-bits (.readUnsignedShort input))
    26 (.readFloat input)
    27 (.readDouble input)
    (28 29 30)
      (*error-handler*
        ::reserved-simple-type
        (format "Additional information simple-value code %d is reserved."
                info))
    31 (*error-handler*
         ::unexpected-break
         "Break encountered outside streaming context.")
    (unknown-simple decoder info)))



;; ## Decoder Implementations

(defrecord ValueDecoder
  []

  Decoder

  (read-value*
    [this input header]
    (let [[mtype info] (decode-header header)]
      (case mtype
        :unsigned-integer (read-positive-integer this input info)
        :negative-integer (read-negative-integer this input info)
        :byte-string      (read-byte-string this input info)
        :text-string      (read-text-string this input info)
        :data-array       (read-array this input info)
        :data-map         (read-map this input info)
        :tagged-value     (read-tagged this input info)
        :simple-value     (read-simple this input info))))

  (unknown-tag
    [this tag value]
    (prn :unknown-tag tag value)
    nil)

  (unknown-simple
    [this value]
    (data/simple-value value)))


(defn decode-value
  "Reads a single CBOR-encoded value from the input stream."
  [^InputStream input & {:keys [eof]}]
  (try
    (let [data-input (DataInputStream. input)
          decoder (map->ValueDecoder {})]
      (read-value decoder data-input))
    (catch EOFException ex
      ; TODO: use dynamic handler?
      (if (nil? eof)
        (throw ex)
        eof))))


;; Ideas:
;; - StrictDecoder
;; - AnalyzingDecoder
