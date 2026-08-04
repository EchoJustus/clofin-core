(ns clofin.idempotency-test
  "Canonicalisation, the digest built on it, and the replay decision.

  These are the functions C-06 turns on. The canonical form decides whether a
  genuine retry is honoured or answered `409` — and a `409` on a genuine retry
  pushes the caller to mint a new key, which is a second payment. See
  docs/ADR/0013-canonical-request-digest-for-idempotency.md."
  (:require [clofin.error :as err]
            [clofin.idempotency :as idem]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(defn- caught [f] (try (f) nil (catch Exception t t)))
(defn- error-type [f] (some-> (caught f) ex-data :clofin/error))

;; ---------------------------------------------------------------------------
;; The canonical form
;; ---------------------------------------------------------------------------

(deftest object-keys-are-sorted-and-whitespace-does-not-survive
  (is (= "{\"a\":1,\"b\":2}" (idem/canonical {"b" 2 "a" 1})))
  (is (= "{\"a\":1,\"b\":2}" (idem/canonical (array-map "b" 2 "a" 1))))
  (testing "an object is unordered, so two encodings of it are one document"
    (is (= (idem/canonical (json/read-str "{\"b\":2,\"a\":1}"))
           (idem/canonical (json/read-str "{ \"a\" : 1 , \"b\" : 2 }"))))))

(deftest array-order-is-preserved
  (testing "an array *is* ordered — sorting one would make two different
            requests digest alike"
    (is (= "[1,2,3]" (idem/canonical [1 2 3])))
    (is (= "[3,2,1]" (idem/canonical [3 2 1])))
    (is (not= (idem/digest {"lines" [{"direction" "debit"} {"direction" "credit"}]})
              (idem/digest {"lines" [{"direction" "credit"} {"direction" "debit"}]})))))

(deftest nesting-is-canonicalised-all-the-way-down
  (is (= "{\"amount\":{\"currency\":\"SGD\",\"minorUnits\":125000},\"z\":[{\"a\":1,\"b\":2}]}"
         (idem/canonical {"z" [(array-map "b" 2 "a" 1)]
                          "amount" (array-map "minorUnits" 125000 "currency" "SGD")}))))

(deftest scalars-have-exactly-one-form-each
  (is (= "null" (idem/canonical nil)))
  (is (= "true" (idem/canonical true)))
  (is (= "false" (idem/canonical false)))
  (is (= "\"SGD\"" (idem/canonical "SGD")))
  (is (= "125000" (idem/canonical 125000)))
  (is (= "-1" (idem/canonical -1)))
  (is (= "0" (idem/canonical 0))))

(deftest numbers-that-are-equal-canonicalise-alike
  (testing "no CloFin field accepts a fractional value; the rule exists so the
            function is total, and so a serialiser's choice is not a conflict"
    (is (= (idem/canonical 1.5) (idem/canonical 1.50M)))
    (is (= (idem/canonical 1) (idem/canonical 1.0)))
    (is (= "100" (idem/canonical 100M)))
    (is (= "125000" (idem/canonical (bigint 125000)))))
  (testing "large integers keep every digit — no exponent, no rounding"
    (is (= "9007199254740993" (idem/canonical 9007199254740993)))))

(deftest strings-are-escaped-minimally-and-only-where-json-requires-it
  (is (= "\"a\\\"b\"" (idem/canonical "a\"b")))
  (is (= "\"a\\\\b\"" (idem/canonical "a\\b")))
  (is (= "\"a\\nb\"" (idem/canonical "a\nb")))
  (is (= "\"a\\tb\"" (idem/canonical "a\tb")))
  (is (= "\"\\u0000\"" (idem/canonical (str (char 0)))))
  (testing "a solidus and non-ASCII text are escapable but not escaped — one
            representation per value, or two encodings of one string differ"
    (is (= "\"a/b\"" (idem/canonical "a/b")))
    (is (= "\"Zürich\"" (idem/canonical "Zürich")))))

(deftest canonicalising-a-canonical-document-is-a-no-op
  (let [document {"b" [1 {"d" true "c" nil}] "a" "x"}
        once     (idem/canonical document)]
    (is (= once (idem/canonical (json/read-str once))))))

(deftest a-value-with-no-rule-raises-rather-than-being-stringified
  (testing "a silent fallback to str is how two different documents come to
            share a digest, which is the one failure this exists to prevent"
    (is (= :validation (error-type #(idem/canonical (Object.)))))
    (is (= :validation (error-type #(idem/canonical {"n" (Object.)}))))
    (is (= :validation (error-type #(idem/canonical {(Object.) 1}))))
    (is (= :validation (error-type #(idem/canonical Double/NaN))))
    (is (= :validation (error-type #(idem/canonical Double/POSITIVE_INFINITY))))))

(deftest keyword-keys-canonicalise-as-their-names
  (testing "so a handler holding a Clojure map digests it the same as the JSON
            that carried it"
    (is (= (idem/canonical {"a" 1 "b" 2}) (idem/canonical {:a 1 :b 2})))))

;; ---------------------------------------------------------------------------
;; The digest
;; ---------------------------------------------------------------------------

(deftest the-digest-is-lower-case-hex-sha-256
  (let [d (idem/digest {"a" 1})]
    (is (= 64 (count d)))
    (is (re-matches #"[0-9a-f]{64}" d))))

(deftest an-absent-body-digests-as-the-empty-object
  (testing "so a bodiless mutation still has something to compare against"
    (is (= (idem/digest nil) (idem/digest {})))))

(deftest a-retry-that-differs-only-in-representation-is-the-same-request
  (let [sent    "{\"organisationId\":\"a\",\"amount\":{\"minorUnits\":125000,\"currency\":\"SGD\"}}"
        retried "{\n  \"amount\": {\n    \"currency\": \"SGD\",\n    \"minorUnits\": 125000\n  },\n  \"organisationId\": \"a\"\n}"]
    (is (= (idem/digest (json/read-str sent)) (idem/digest (json/read-str retried)))
        "a 409 here would push the caller to mint a new key — a second payment")))

(deftest a-retry-that-differs-in-substance-is-a-different-request
  (is (not= (idem/digest {"amount" {"currency" "SGD" "minorUnits" 125000}})
            (idem/digest {"amount" {"currency" "SGD" "minorUnits" 125001}})))
  (is (not= (idem/digest {"a" 1}) (idem/digest {"a" "1"}))
      "a number and the string of it are different values")
  (testing "a member CloFin does not recognise still changes the digest — the
            field it ignores today is the one a later increment gives meaning"
    (is (not= (idem/digest {"a" 1}) (idem/digest {"a" 1 "unknown" true})))))

(defspec re-encoding-a-document-never-changes-its-digest 200
  (prop/for-all [document (gen/recursive-gen
                           (fn [inner]
                             (gen/one-of [(gen/map (gen/not-empty gen/string-alphanumeric) inner
                                                   {:max-elements 4})
                                          (gen/vector inner 0 4)]))
                           (gen/one-of [gen/string gen/large-integer gen/boolean
                                        (gen/return nil)]))]
    (= (idem/digest document)
       ;; Round-tripping through JSON is what a caller's HTTP client does to a
       ;; retry: same document, different bytes.
       (idem/digest (json/read-str (json/write-str document))))))

(defspec adding-any-member-changes-the-digest 100
  (prop/for-all [document (gen/map (gen/not-empty gen/string-alphanumeric)
                                   gen/large-integer {:max-elements 5})
                 extra-key (gen/not-empty gen/string-alphanumeric)
                 extra-value gen/large-integer]
    (or (contains? document extra-key)
        (not= (idem/digest document)
              (idem/digest (assoc document extra-key extra-value))))))

;; ---------------------------------------------------------------------------
;; The key itself
;; ---------------------------------------------------------------------------

(deftest a-mutating-request-must-carry-a-key
  (testing "PR-040 — a caller that has not thought about retries is exactly the
            caller a retry will hurt"
    (is (= :validation (error-type #(idem/read-key nil))))
    (is (= :validation (error-type #(idem/read-key ""))))
    (is (= :validation (error-type #(idem/read-key "   "))))
    (is (= 400 (:status (get err/error-types :validation)))
        "and the answer is 400, not a quiet execution")))

(deftest a-key-is-trimmed-bounded-and-free-of-control-characters
  (is (= "abc" (idem/read-key "  abc  ")))
  (is (= (apply str (repeat idem/max-key-length "k"))
         (idem/read-key (apply str (repeat idem/max-key-length "k")))))
  (is (= :validation (error-type #(idem/read-key (apply str (repeat (inc idem/max-key-length) "k"))))))
  (testing "rejected rather than stripped — stripping would make two different
            keys compare equal, in the one field whose job is telling requests apart"
    (is (= :validation (error-type #(idem/read-key (str "a" (char 0) "b")))))
    (is (= :validation (error-type #(idem/read-key "a\u0007b"))))))

;; ---------------------------------------------------------------------------
;; The replay decision
;; ---------------------------------------------------------------------------

(deftest a-matching-digest-is-a-replay-and-a-differing-one-is-a-conflict
  (is (true? (idem/same-request? "abc" "abc")))
  (is (false? (idem/same-request? "abc" "def")))
  (is (true? (idem/assert-same-request! "abc" "abc")))
  (is (= :conflict (error-type #(idem/assert-same-request! "abc" "def")))))

(deftest a-conflict-does-not-describe-the-request-it-remembers
  (testing "a caller guessing keys learns nothing about what the first request held"
    (let [t (caught #(idem/assert-same-request! "stored-digest" "incoming-digest"))
          data (ex-data t)]
      (is (= :conflict (:clofin/error data)))
      (is (not (re-find #"stored-digest" (pr-str data))))
      (is (not (re-find #"stored-digest" (ex-message t)))))))
