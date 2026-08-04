(ns clofin.api.entries
  "Journal entry endpoints.

  This is where money moves, so it is worth being explicit about what each
  status code means here (ADR-0012):

  - `400` — the request could not be understood: a malformed UUID, a missing
    field, a line amount that is not an integer, fewer than two lines.
  - `422` — the request was understood and cannot be carried out: the entry
    does not balance, an account is frozen, an account is not in this
    organisation.
  - `409` — the entry, or its reversal, has already been posted.

  A payment client branches on that distinction: the first class is its own
  bug, the second is a business outcome it must show to a human."
  (:require [clofin.api.principal :as principal]
            [clofin.api.wire :as wire]
            [clofin.db.core :as db]
            [clofin.error :as err]
            [clofin.http.response :as resp]
            [clofin.ledger.account :as account]
            [clofin.ledger.entry :as entry]
            [clofin.ledger.repository :as ledger]
            [clofin.ledger.service :as ledger-service]
            [clofin.money :as money]))

(defn- read-journal-line
  [raw index]
  (when-not (map? raw)
    (err/invalid! "Each journal line must be a JSON object" {:index index}))
  ;; `entry/line` is the domain's own validation — direction is a known
  ;; direction, the amount is a well-formed positive amount. Reusing it here
  ;; rather than restating those rules is what keeps the two from drifting.
  (entry/line
   {:account-id (wire/read-uuid-field raw "accountId")
    :direction  (wire/read-enum (get raw "direction") "direction" account/directions)
    :amount     (wire/read-money (get raw "amount") "amount")}))

(defn- read-lines
  [body]
  (let [raw (get body "lines")]
    (when-not (sequential? raw)
      (err/invalid! "Field 'lines' must be an array of journal lines" {:field "lines"}))
    (let [lines (into [] (map-indexed (fn [i l] (read-journal-line l i))) raw)]
      (when (< (count lines) 2)
        (err/invalid! "Journal entry requires at least two lines"
                      {:line-count (count lines)}))
      lines)))

(defn- assert-balanced!
  "Reject an unbalanced entry as `422`, naming the shortfall per currency.

  `entry/entry` also refuses an unbalanced entry, and the database refuses one
  again at commit — three checks, deliberately (ADR-0008). This one exists
  because it is the only one that can produce the response the API contract
  promises: a `422` whose `errors` names what is missing, in which currency.
  It calls `entry/imbalance` rather than recomputing the sums, so the two
  cannot disagree."
  [lines]
  (let [gaps (entry/imbalance lines)]
    (when (seq gaps)
      (err/fail! :unprocessable
                 "Journal entry does not balance: total debits must equal total credits"
                 {:imbalance (into {} (map (fn [[currency amount]]
                                             [currency (money/format-amount amount)]))
                                   gaps)}))))

(defn post-entry
  "`POST /journal-entries` — record one economic event.

  The entry id is generated here: the domain layer never generates identifiers,
  and accepting one from the caller would be an idempotency mechanism arrived
  at by accident rather than designed (TASK-002).

  The transaction is opened here because something must open it and a service
  may not (`ARCHITECTURE.md` §4): the entry, its lines and its
  `journal-entry.posted` audit event commit together or not at all (C-05,
  invariant I9). That includes the case where the *database* refuses the entry
  — the zero-sum and completeness guards are deferred, so they fire at this
  commit, and the event is inside it.

  Parsing, the principal and the balance check all happen *before* the
  transaction, so a `401`, a `403`, a malformed-body `400` and the unbalanced
  `422` never open one. What runs inside it is `entry/entry` and the
  posting-time rules that need stored state — an unknown or frozen account, a
  currency mismatch — so those refusals roll back an open transaction with
  nothing written."
  [pool]
  (fn [request]
    (let [body  (wire/read-object request)
          ;; Posting a journal entry moves money in the ledger, so it is a
          ;; `controller` right rather than an operator one (C-08).
          [actor organisation-id] (principal/for-request pool request :entry/post body)
          lines (read-lines body)
          _     (assert-balanced! lines)
          reference (get body "reference")
          _     (when-not (map? reference)
                  (err/invalid! "Field 'reference' must name the business object that caused the entry"
                                {:field "reference" :known (vec (sort (map name entry/reference-types)))}))
          candidate {:id              (random-uuid)
                     :organisation-id organisation-id
                     :occurred-at     (wire/read-instant-field body "occurredAt")
                     :narrative       (wire/read-string-field body "narrative")
                     :reference       {:type (wire/read-enum (get reference "type") "reference.type"
                                                             entry/reference-types)
                                       :id   (wire/read-uuid-field reference "id")}
                     :lines           lines}
          posted (db/with-transaction [tx pool]
                   (ledger-service/post-entry!
                    tx {:entry          candidate
                        :actor-id       (:id actor)
                        :correlation-id (:correlation-id request)}))]
      (resp/created (str "/journal-entries/" (:id posted)
                         "?organisationId=" (:organisation-id posted))
                    (wire/entry->wire posted)))))

(defn show
  "`GET /journal-entries/:id` — the entry a `201` Location header points at."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :entry/read)
          id (wire/read-uuid (get-in request [:path-params :id]) "id")]
      (if-let [found (ledger/find-entry pool organisation-id id)]
        (resp/ok (wire/entry->wire found))
        (err/not-found! "No such journal entry in this organisation" {:id (str id)})))))
