(ns clofin.ledger.service
  "The ledger's audited writes: opening an account, and posting a journal
  entry, each bound to the audit event that records it.

  Like `clofin.payments.approval-service`, every function here takes a `tx` — a
  connection inside a transaction the *caller* owns — and requires no
  `clofin.db.*` namespace at all. That is the control, not a style
  (`ARCHITECTURE.md` §4, [C-05], invariant I9): a service able to open its own
  connection is a service able to write an audit event outside the change it
  describes, and
  `clofin.ledger.purity-test/a-service-cannot-open-its-own-transaction` fails
  the build if this namespace acquires one.

  **Why this sits beside the repository rather than inside it.** The repository
  persists one aggregate and translates the database's refusals into domain
  outcomes; it does not know who asked or under which correlation id, and it is
  called by tests that assert persistence with no audit trail in view. Composing
  the two writes here keeps that seam intact and puts the pairing in one
  readable place — the same split `clofin.payments.approval-service` already
  makes for approvals.

  **Why it is not in the handler either.** An audit event written by
  `clofin.api.accounts` would be a control that exists only for callers arriving
  through `clofin.api.accounts`. That is the shape of audit finding **F-001**,
  which found segregation of duties enforced in a handler, and it is the shape
  worth not repeating. The handler opens the transaction — that is a transport
  concern, and something must — and this namespace decides what is written into
  it.

  [C-05]: docs/COMPLIANCE.md"
  (:require [clofin.audit :as audit]
            [clofin.audit.repository :as audit-store]
            [clofin.ledger.repository :as ledger]))

(defn create-account!
  "Open a ledger account and record it, on the caller's transaction.

  Returns the account as stored. A refused opening — a duplicate code, an
  unknown organisation — throws before the audit write is reached and takes the
  transaction with it, so a `409` or a `422` leaves no event behind."
  [tx {:keys [account actor-id correlation-id]}]
  (let [acct (ledger/create-account! tx account)]
    ;; Same transaction as the insert above (C-05, PR-075, invariant I9).
    (audit-store/record! tx {:organisation-id (:organisation-id acct)
                             :actor-id        actor-id
                             :action          "account.created"
                             :subject-type    "account"
                             :subject-id      (:id acct)
                             ;; The account did not exist a moment ago, so there
                             ;; is no before — the same nil that marks every
                             ;; creation in this trail.
                             :before          nil
                             :after           (audit/account-subject acct)
                             :correlation-id  correlation-id})
    acct))

(defn post-entry!
  "Post a journal entry and record it, on the caller's transaction.

  Returns the entry as posted. Two ways this can fail without leaving an event,
  and both matter to invariant I9:

  1. **The repository refuses.** An unknown account, a frozen account, a second
     reversal — `clofin.ledger.repository/post-entry!` raises a domain error
     before returning, so the audit write is never reached.
  2. **The database refuses at `commit`.** The zero-sum and completeness guards
     on a journal entry are `deferrable initially deferred`, so they fire when
     the caller's transaction commits — after this function has returned and
     after the event has been written. The event is inside that transaction, so
     it goes down with it. That is the whole reason `record!` cannot open a
     connection of its own: an event committed on its own connection would have
     survived a posting the database then refused."
  [tx {:keys [entry actor-id correlation-id]}]
  (let [posted (ledger/post-entry! tx entry)]
    (audit-store/record! tx {:organisation-id (:organisation-id posted)
                             :actor-id        actor-id
                             :action          "journal-entry.posted"
                             :subject-type    "journal-entry"
                             :subject-id      (:id posted)
                             ;; A posted entry is never amended (C-03): a
                             ;; correction is a reversing entry with its own id
                             ;; and its own event. So there is no before here,
                             ;; and there never will be.
                             :before          nil
                             :after           (audit/journal-entry-subject posted)
                             :correlation-id  correlation-id})
    posted))
