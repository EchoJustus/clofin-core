(ns clofin.idempotency
  "Idempotency keys: canonicalisation, digest, and the replay decision.

  A caller-supplied `Idempotency-Key` makes a retry safe. The mechanism needs
  one thing beyond storage: a way to tell a genuine retry from a *different*
  request that reused a key. That is what the digest is for, and what it is
  computed over decides whether a correct retry is honoured or rejected —
  see docs/ADR/0013-canonical-request-digest-for-idempotency.md.

  **Idempotency is not caching.** A cache may miss and re-execute; a key may
  not. Nothing here writes anything: storage lives in
  `clofin.idempotency.repository`, which is where the key and the effect it
  protects are committed together.

  Pure: no database, no clock, no identifier generation."
  (:require [clofin.error :as err]
            [clojure.string :as str])
  (:import [java.math BigDecimal]
           [java.security MessageDigest]
           [java.util HexFormat]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; The key itself
;; ---------------------------------------------------------------------------

(def max-key-length
  "A key is caller-chosen and becomes half of a primary key. Bounded because an
  unbounded index term is a caller's decision about CloFin's storage."
  255)

(defn read-key
  "Validate and normalise a caller-supplied idempotency key.

  The header is **mandatory** on every mutating endpoint (PR-040): a request
  that omits it is `400` rather than being quietly executed, because a caller
  that has not thought about retries is exactly the caller a retry will hurt.

  Control characters are rejected rather than stripped. Stripping would make
  two different keys compare equal, which is a collision in the one field whose
  whole job is telling requests apart."
  [value]
  (when-not (and (string? value) (not (str/blank? value)))
    (err/invalid!
     "Header 'Idempotency-Key' is required on every mutating request"
     {:header "Idempotency-Key"}))
  (let [key (str/trim value)]
    (when (> (count key) max-key-length)
      (err/invalid! (str "Header 'Idempotency-Key' must be at most " max-key-length
                         " characters")
                    {:header "Idempotency-Key" :max-length max-key-length}))
    (when (some (fn [c] (Character/isISOControl (char c))) key)
      (err/invalid! "Header 'Idempotency-Key' must not contain control characters"
                    {:header "Idempotency-Key"}))
    key))

;; ---------------------------------------------------------------------------
;; Canonical serialisation
;; ---------------------------------------------------------------------------
;;
;; JSON has no canonical form: `{"a":1,"b":2}` and `{ "b": 2, "a": 1 }` are the
;; same document. Digesting raw bytes would answer `409` to a retry that differs
;; only in whitespace or key order — which is the ordinary output of an HTTP
;; client library, and which would push the caller to mint a new key. A new key
;; is a second payment.
;;
;; The rules below follow RFC 8785 for the value types CloFin's API accepts.
;; They are stated here rather than deferred to a library because they are the
;; boundary between "honoured retry" and "409", and that boundary is a decision
;; (ADR-0013).

(defn- escape-string
  "JSON string escaping, minimal and deterministic: one representation per
  value. Only what JSON requires is escaped — a `/` or a non-ASCII character
  escaped optionally would give the same string two encodings."
  [^String s]
  (let [sb (StringBuilder. (+ 2 (.length s)))]
    (.append sb \")
    (dotimes [i (.length s)]
      (let [c (.charAt s i)]
        (case c
          \" (.append sb "\\\"")
          \\ (.append sb "\\\\")
          \backspace (.append sb "\\b")
          \formfeed  (.append sb "\\f")
          \newline   (.append sb "\\n")
          \return    (.append sb "\\r")
          \tab       (.append sb "\\t")
          (if (< (int c) 0x20)
            (.append sb (format "\\u%04x" (int c)))
            (.append sb c)))))
    (.append sb \")
    (.toString sb)))

(defn- canonical-number
  "A number in one form.

  Integers render exactly, with no exponent — `125000`, never `1.25e5`. A
  non-integer is normalised through `BigDecimal` with trailing zeros stripped,
  so that `1.50` and `1.5` digest alike; they are the same number, and a caller
  should not be told its retry conflicts because its serialiser emitted the
  other one. No CloFin field accepts a fractional value — money is integer
  minor units (ADR-0003) — so this rule exists to make the function total, not
  because the case is reachable."
  [n]
  (cond
    (integer? n) (str (biginteger n))
    (decimal? n) (.toPlainString (.stripTrailingZeros ^BigDecimal n))
    (or (instance? Double n) (instance? Float n))
    (let [d (double n)]
      (when (or (Double/isNaN d) (Double/isInfinite d))
        (err/invalid! "A request body cannot carry a non-finite number"
                      {:value (str n)}))
      (if (== d (Math/rint d))
        (str (biginteger (BigDecimal/valueOf d)))
        (.toPlainString (.stripTrailingZeros (BigDecimal/valueOf d)))))
    :else (.toPlainString (.stripTrailingZeros (BigDecimal. (str n))))))

(defn canonical
  "One deterministic string for one JSON document.

  Object keys are sorted by UTF-16 code unit and no insignificant whitespace
  survives, so two encodings of the same document produce one string. **Array
  order is preserved** — an array is ordered, and sorting one would make two
  genuinely different requests digest alike.

  Throws on a value it has no rule for, rather than falling back to `str`. A
  silent fallback is how two different documents come to share a digest, which
  is the single failure this function exists to prevent."
  [value]
  (cond
    (nil? value)     "null"
    (true? value)    "true"
    (false? value)   "false"
    (string? value)  (escape-string value)
    (number? value)  (canonical-number value)

    (map? value)
    (str "{"
         (str/join ","
                   (map (fn [[k v]]
                          (let [k (cond (string? k)  k
                                        (keyword? k) (name k)
                                        :else (err/invalid!
                                               "A request body object key must be a string"
                                               {:key (str k)}))]
                            (str (escape-string k) ":" (canonical v))))
                        ;; Sorted by the key's own characters, so ordering does
                        ;; not depend on the map implementation that carried it.
                        (sort-by (fn [[k _]] (if (keyword? k) (name k) (str k))) value)))
         "}")

    (sequential? value)
    (str "[" (str/join "," (map canonical value)) "]")

    :else
    (err/invalid! (str "A request body cannot carry a " (.getName (class value)))
                  {:type (.getName (class value))})))

(defn- sha256 ^String [^String s]
  (-> (MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes s "UTF-8"))
      (->> (.formatHex (HexFormat/of)))))

(defn digest
  "SHA-256 of the canonical serialisation of `body`, as lower-case hex.

  An absent body digests as the empty object, so that a bodiless mutation still
  has a digest to compare against — `nil` and `{}` are the same request."
  [body]
  (sha256 (canonical (or body {}))))

;; ---------------------------------------------------------------------------
;; The replay decision
;; ---------------------------------------------------------------------------

(defn same-request?
  "True when a stored digest and an incoming one describe the same request."
  [stored-digest incoming-digest]
  (= stored-digest incoming-digest))

(defn assert-same-request!
  "Throw a `:conflict` unless a seen key is being replayed by the request that
  created it.

  This is the case the control turns on. Returning the stored response to a
  *different* request would tell a caller that an amount it never sent had been
  accepted; executing it would be the second payment the key exists to prevent.
  Neither is acceptable, so the answer is `409` and nothing runs.

  The stored request is not described in the error. A caller that reused a key
  by accident is told so; one that is guessing keys learns nothing about what
  the first request contained."
  [stored-digest incoming-digest]
  (when-not (same-request? stored-digest incoming-digest)
    (err/conflict!
     (str "This Idempotency-Key has already been used for a different request. "
          "Retry the original request unchanged, or use a new key.")
     {:header "Idempotency-Key"}))
  true)
