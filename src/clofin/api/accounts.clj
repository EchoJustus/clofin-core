(ns clofin.api.accounts
  "Ledger account endpoints, including account statements.

  Every lookup here is scoped by organisation, and the organisation now comes
  from the authenticated principal rather than from the request
  (`clofin.api.principal`). The scoping is still applied in the repository
  query rather than checked after the fact — a query that returns another
  tenant's row and then discards it has already read it.

  Every operation names the permission it needs (C-08). `:account/create` is a
  `controller` right; reading is broader, because an operator who cannot see
  the chart of accounts cannot raise a payment against one."
  (:require [clofin.api.principal :as principal]
            [clofin.api.wire :as wire]
            [clofin.db.core :as db]
            [clofin.error :as err]
            [clofin.http.response :as resp]
            [clofin.ledger.account :as account]
            [clofin.ledger.repository :as ledger]
            [clofin.ledger.service :as ledger-service]))

(defn- find-account!
  "The account, or a 404. Shared by every handler that addresses one."
  [pool organisation-id id]
  (or (ledger/find-account pool organisation-id id)
      (err/not-found! "No such account in this organisation" {:id (str id)})))

(defn create
  "`POST /accounts` — open a ledger account.

  Accounts are created `active`. There is no way to open one frozen or closed:
  an account nobody can post to is not something a caller means to ask for, and
  a status transition is a separate operation with its own audit requirements.

  The transaction is opened here because something must open it and a service
  may not (`ARCHITECTURE.md` §4): the account row and its `account.created`
  audit event commit together or not at all (C-05, invariant I9). The request
  is parsed and the principal resolved *before* the transaction, so a `400`,
  `401` or `403` never opens one."
  [pool]
  (fn [request]
    (let [body (wire/read-object request)
          [actor organisation-id] (principal/for-request pool request :account/create body)
          candidate {:id              (random-uuid)
                     :organisation-id organisation-id
                     :code            (wire/read-string-field body "code")
                     :name            (wire/read-string-field body "name")
                     :type            (wire/read-enum (get body "type") "type"
                                                      (set (keys account/account-types)))
                     :currency        (wire/read-string-field body "currency")
                     :status          :active}
          acct (db/with-transaction [tx pool]
                 (ledger-service/create-account!
                  tx {:account        candidate
                      :actor-id       (:id actor)
                      :correlation-id (:correlation-id request)}))]
      (resp/created (str "/accounts/" (:id acct) "?organisationId=" (:organisation-id acct))
                    (wire/account->wire acct)))))

(defn show
  "`GET /accounts/:id`."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :account/read)
          id   (wire/read-uuid (get-in request [:path-params :id]) "id")
          acct (find-account! pool organisation-id id)]
      (resp/ok (wire/account->wire acct)))))

(defn index
  "`GET /accounts` — the organisation's chart of accounts, by code.

  Capped rather than paginated; the cap is reported so a caller can tell a full
  answer from a partial one. See ADR-0011."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :account/read)
          accounts (ledger/list-accounts pool organisation-id)]
      (resp/ok {"accounts" (mapv wire/account->wire accounts)
                "count"    (count accounts)
                "limit"    ledger/row-cap}))))

(defn statement
  "`GET /accounts/:id/statement` — opening balance, movements, closing balance.

  The period is half-open: `from` is included, `to` is not. That is what makes
  consecutive statements chain exactly rather than double-counting whatever was
  posted on the boundary (ADR-0011)."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :account/read)
          id   (wire/read-uuid (get-in request [:path-params :id]) "id")
          from (wire/read-instant (wire/read-query-param request "from") "from")
          to   (wire/read-instant (wire/read-query-param request "to") "to")
          acct (find-account! pool organisation-id id)]
      (resp/ok (wire/statement->wire (ledger/statement pool acct {:from from :to to})
                                     ledger/row-cap)))))
