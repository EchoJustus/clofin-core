(ns clofin.api.wire-test
  "Parsing and serialisation, with no database and no HTTP.

  These are the rules that decide whether a request is a `400`. They are worth
  testing in isolation because every one of them is a place where a caller's
  mistake either becomes a clear message or becomes a stack trace."
  (:require [clofin.api.wire :as wire]
            [clofin.error :as err]
            [clofin.ledger.account :as account]
            [clofin.money :as money]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant]))

(defn- rejection
  "The domain error data from a call expected to fail validation."
  [f]
  (try
    (f)
    (is false "expected a validation error, but the call succeeded")
    nil
    (catch clojure.lang.ExceptionInfo e
      (is (err/domain-error? e))
      (assoc (ex-data e) :message (ex-message e)))))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(deftest a-body-must-be-a-json-object
  (doseq [body [nil [] "text" 42]]
    (testing (str "body: " (pr-str body))
      (is (= :validation
             (:clofin/error (rejection #(wire/read-object {:json-body body}))))))))

(deftest a-missing-field-is-named
  (let [failure (rejection #(wire/read-string-field {"other" "value"} "narrative"))]
    (is (= "narrative" (:field failure)))
    (is (re-find #"'narrative' is required" (:message failure)))))

(deftest a-blank-string-counts-as-missing
  (testing "a narrative of spaces explains nothing, which is what the field is for"
    (is (some? (rejection #(wire/read-string-field {"narrative" "   "} "narrative"))))))

(deftest a-string-field-is-trimmed
  (is (= "Opening balance" (wire/read-string-field {"narrative" "  Opening balance  "} "narrative"))))

(deftest a-uuid-field-is-parsed-or-named
  (let [id (random-uuid)]
    (is (= id (wire/read-uuid-field {"organisationId" (str id)} "organisationId"))))
  (testing "a malformed identifier names the field rather than leaking the Java exception"
    (let [failure (rejection #(wire/read-uuid-field {"organisationId" "not-a-uuid"} "organisationId"))]
      (is (= "organisationId" (:field failure)))
      (is (re-find #"must be a UUID" (:message failure))))))

(deftest an-instant-must-carry-a-zone
  (is (= (Instant/parse "2026-08-02T10:15:00Z")
         (wire/read-instant-field {"occurredAt" "2026-08-02T10:15:00Z"} "occurredAt")))
  (testing "a local time with no offset is ambiguous by exactly the amount that
            makes a statement period wrong at its boundaries"
    (doseq [value ["2026-08-02" "2026-08-02T10:15:00" "yesterday" ""]]
      (is (some? (rejection #(wire/read-instant-field {"occurredAt" value} "occurredAt")))
          (str "should reject " (pr-str value))))))

(deftest an-enum-lists-what-was-allowed
  (is (= :debit (wire/read-enum "debit" "direction" account/directions)))
  (let [failure (rejection #(wire/read-enum "sideways" "direction" account/directions))]
    (is (= "direction" (:field failure)))
    (is (= ["credit" "debit"] (:known failure))
        "a caller should not have to guess what the accepted values were")))

(deftest an-amount-must-be-integer-minor-units
  (is (= (money/of "SGD" 125000)
         (wire/read-money {"currency" "SGD" "minorUnits" 125000} "amount")))
  (testing "money is never a float, including on the wire"
    (is (some? (rejection #(wire/read-money {"currency" "SGD" "minorUnits" 1250.5} "amount")))))
  (testing "an unsupported currency is refused rather than assumed to have two decimals"
    (is (some? (rejection #(wire/read-money {"currency" "XYZ" "minorUnits" 1} "amount")))))
  (testing "an amount that is not an object at all"
    (is (some? (rejection #(wire/read-money "125000" "amount"))))))

(deftest the-stated-organisation-is-read-from-the-body-on-a-write-and-the-query-on-a-read
  (let [id (random-uuid)]
    (is (= id (wire/read-stated-organisation-id {} {"organisationId" (str id)})))
    (is (= id (wire/read-stated-organisation-id {:query-params {"organisationId" (str id)}})))
    (testing "and is now OPTIONAL, because the organisation acted on comes from
              the authenticated principal — this value is verified against it,
              not trusted as tenancy scoping (TASK-003)"
      (is (nil? (wire/read-stated-organisation-id {:query-params {}})))
      (is (nil? (wire/read-stated-organisation-id {} {})))
      (is (nil? (wire/read-stated-organisation-id {:query-params {"organisationId" ""}}))))
    (testing "a value that is present and malformed is still a 400 — it was sent, so it must parse"
      (is (some? (rejection #(wire/read-stated-organisation-id {} {"organisationId" "nope"}))))
      (is (some? (rejection #(wire/read-stated-organisation-id
                              {:query-params {"organisationId" "nope"}})))))))

;; ---------------------------------------------------------------------------
;; Writing
;; ---------------------------------------------------------------------------

(def ^:private an-account
  {:id (random-uuid)
   :organisation-id (random-uuid)
   :code "2100-CLIENT-PAYABLE"
   :name "Client payable"
   :type :liability
   :currency "SGD"
   :status :active})

(deftest an-account-serialises-with-its-normal-balance
  (let [wire (wire/account->wire an-account)]
    (is (= "2100-CLIENT-PAYABLE" (get wire "code")))
    (is (= "liability" (get wire "type")))
    (is (= "credit" (get wire "normalBalance"))
        "derived, and included because it is what makes the sign of a balance readable")
    (testing "identifiers and enumerations reach the wire as strings"
      (is (string? (get wire "id")))
      (is (string? (get wire "organisationId")))
      (is (string? (get wire "status"))))))

(deftest an-entry-serialises-with-its-lines-in-order
  (let [entry {:id (random-uuid)
               :organisation-id (random-uuid)
               :occurred-at (Instant/parse "2026-08-02T10:15:00Z")
               :narrative "Opening balance"
               :reference {:type :opening-balance :id (random-uuid)}
               :lines [{:account-id (random-uuid) :direction :debit :amount (money/of "SGD" 125000)}
                       {:account-id (random-uuid) :direction :credit :amount (money/of "SGD" 125000)}]}
        wire (wire/entry->wire entry)]
    (is (= "2026-08-02T10:15:00Z" (get wire "occurredAt")))
    (is (= "opening-balance" (get-in wire ["reference" "type"])))
    (is (= ["debit" "credit"] (mapv #(get % "direction") (get wire "lines"))))
    (is (= {"currency" "SGD" "minorUnits" 125000}
           (get-in wire ["lines" 0 "amount"]))
        "minor units stay an integer so no consumer has to parse a decimal")
    (testing "recordedAt is absent on a value that has not been read back"
      (is (not (contains? wire "recordedAt"))))
    (testing "and present on one that has"
      (is (contains? (wire/entry->wire (assoc entry :recorded-at (Instant/now)))
                     "recordedAt")))))

(deftest a-statement-states-its-cap-whether-or-not-it-was-reached
  (let [statement {:account an-account
                   :from (Instant/parse "2026-02-01T00:00:00Z")
                   :to (Instant/parse "2026-03-01T00:00:00Z")
                   :opening-balance (money/of "SGD" 100000)
                   :closing-balance (money/of "SGD" 121000)
                   :movements []
                   :truncated? false}
        wire (wire/statement->wire statement 500)]
    (is (= {"currency" "SGD" "minorUnits" 121000} (get wire "closingBalance")))
    (is (false? (get wire "truncated"))
        "stated even when false: a consumer should not infer from an absent field")
    (is (= 500 (get wire "movementCap")))))
