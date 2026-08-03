(ns clofin.idempotency.repository
  "Storage for idempotency keys, and the at-most-once execution built on it.

  This namespace is the enforcement point for [C-06] — a retry cannot cause a
  second payment. Two properties make that true, and neither is optional.

  **The key is written in the same transaction as the effect it protects.**
  Not before, not after. A key stored first and a crash before the effect
  refuses a legitimate retry; an effect performed first and a crash before the
  key leaves a payment made with no record that it was made — and the next
  retry makes it again.

  **The guarantee is the primary key, not a check in this code.** Replay
  protection written as a read-then-write in application code is a race, and
  the window between the read and the write is exactly long enough for two
  concurrent retries to both execute. Here the second inserter blocks on
  `idempotency_key_pkey` until the first commits, fails on it, and returns what
  the first stored. The read at the top of `execute-once!` is an optimisation
  that saves work on an already-settled key; delete it and the guarantee is
  unchanged.

  See docs/ADR/0013-canonical-request-digest-for-idempotency.md for what the
  digest is computed over.

  [C-06]: docs/COMPLIANCE.md"
  (:require [clofin.db.core :as db]
            [clofin.idempotency :as idem]
            [clojure.data.json :as json]))

(def ^:private key-constraint
  "The composite primary key of `idempotency_key`. Named so that a unique
  violation raised by something *else* inside the effect is rethrown as the
  defect it is, rather than being mistaken for a replay and answered with an
  unrelated stored response."
  "idempotency_key_pkey")

(defn- stored
  "The record for a key that has already been committed, or nil."
  [source organisation-id key]
  (db/query-one source
                ["select request_digest, response_status, response_body
                    from idempotency_key
                   where organisation_id = ? and key = ?"
                 organisation-id key]))

(defn- ->replay
  "The stored response, as an outcome a handler can return.

  `:body` is the stored JSON **string**, returned verbatim so that a replay is
  byte-identical to the response the first call produced. `:data` is the same
  document decoded, which is what lets a handler rebuild a header — a `Location`
  — that is a function of the resource rather than of the request."
  [row]
  (let [body (:response-body row)]
    {:status    (int (:response-status row))
     :body      body
     :data      (json/read-str body)
     :replayed? true}))

(defn find-response
  "The response stored against a key, or nil. Exposed for tests and diagnosis."
  [source organisation-id key]
  (some-> (stored source organisation-id key) ->replay))

(defn execute-once!
  "Run `effect` at most once for `(organisation-id, key)`.

  `effect` is `(fn [tx] {:status <int> :body <JSON-ready data>})`, called with a
  connection inside the transaction that also writes the key — so whatever it
  persists commits with the key or not at all.

  Returns `{:status :body :data :replayed?}`, where `:body` is the response as
  a JSON string.

  Four outcomes, matching the semantics the API contract promises:

  | Situation | What happens |
  |---|---|
  | Key unseen | `effect` runs; key, digest, status and body are stored with it |
  | Key seen, digest identical | The stored response is returned. `effect` does not run |
  | Key seen, digest different | `409`. `effect` does not run |
  | Two concurrent identical keys | One wins on the primary key; the loser's work is rolled back and it returns the winner's response |

  An `effect` that throws takes the key row down with it, so a request that
  failed does not consume its key. That is deliberate: a caller correcting a
  rejected request and retrying with the same key gets a fresh execution, not a
  `409` telling it the body changed."
  [pool {:keys [organisation-id key digest]} effect]
  (if-let [row (stored pool organisation-id key)]
    ;; Already settled. Correctness does not rest on this read — the primary
    ;; key below does — but there is no reason to do the work and roll it back.
    (do (idem/assert-same-request! (:request-digest row) digest)
        (->replay row))
    (try
      (db/with-transaction*
        pool
        (fn [tx]
          ;; Claim the key *before* doing the work. A concurrent request
          ;; carrying the same key blocks on this insert until this transaction
          ;; ends, so it never performs the effect at all — rather than
          ;; performing it and having the rollback undo it. The response is not
          ;; known yet, so the row is completed below in the same transaction;
          ;; no other transaction can observe the placeholder.
          (db/execute! tx
                       ["insert into idempotency_key
                           (organisation_id, key, request_digest, response_status, response_body)
                         values (?, ?, ?, ?, ?)"
                        organisation-id key digest 0 ""])
          (let [{:keys [status body]} (effect tx)
                encoded (json/write-str body)]
            (db/execute! tx
                         ["update idempotency_key
                              set response_status = ?, response_body = ?
                            where organisation_id = ? and key = ?"
                          status encoded organisation-id key])
            {:status status :body encoded :data body :replayed? false})))
      (catch Exception t
        (let [{:keys [sql-state constraint]} (db/violation t)]
          (if (and (= sql-state (:unique-violation db/sql-states))
                   (= key-constraint constraint))
            ;; Lost the race. The winner blocked this insert until it committed,
            ;; so its row is visible now. This transaction rolled back whole,
            ;; which is what makes "exactly one effect occurred" true.
            (let [row (or (stored pool organisation-id key) (throw t))]
              (idem/assert-same-request! (:request-digest row) digest)
              (->replay row))
            (throw t)))))))
