(ns clofin.api.audit
  "Audit endpoints: reading the trail, and extracting an evidence pack.

  An append-only table nobody can query completely is a table that answers an
  auditor's question with \"probably\". These two operations are the other half
  of C-05's value — PR-074 asks that an auditor be able to extract a complete
  evidence pack for a payment or a period, and \"complete\" is a property the
  response has to state rather than one the reader has to assume.

  Both are read-only and both require `:audit/read`, which `operator` and
  `approver` do not hold. An operator who could read the whole organisation's
  audit trail could see which approvers act on what and when, which is
  reconnaissance rather than transparency.

  **Digests, not payloads.** An event says *that* something changed and, by
  digest, *what it changed to*; it does not carry the counterparty name. That
  is a deliberate trade, made in
  docs/ADR/0016-audit-events-store-digests-not-payloads.md, and what it costs
  an auditor is stated there rather than discovered here."
  (:require [clofin.api.principal :as principal]
            [clofin.api.wire :as wire]
            [clofin.audit.repository :as audit-store]
            [clofin.error :as err]
            [clofin.http.response :as resp]))

(defn index
  "`GET /audit/events` — an organisation's audit trail, most recent first.

  Narrowed by `?action=`, `?subjectId=`, `?from=` and `?to=`. The period is
  half-open — `from` included, `to` excluded — the same convention the account
  statement uses, so consecutive extractions chain exactly rather than
  double-counting whatever landed on the boundary.

  Capped rather than paginated, with the cap and a `truncated` flag on every
  response (ADR-0011). Of all the places in CloFin to silently stop at a limit,
  this is the worst."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :audit/read)
          action     (wire/read-optional-query-param request "action")
          subject-id (some-> (wire/read-optional-query-param request "subjectId")
                             (wire/read-uuid "subjectId"))
          from (some-> (wire/read-optional-query-param request "from")
                       (wire/read-instant "from"))
          to   (some-> (wire/read-optional-query-param request "to")
                       (wire/read-instant "to"))
          {:keys [events truncated?]}
          (audit-store/list-events pool organisation-id
                                   {:action action :subject-id subject-id
                                    :from from :to to})]
      (resp/ok {"auditEvents" (mapv wire/audit-event->wire events)
                "count"     (count events)
                "limit"     audit-store/row-cap
                "truncated" (boolean truncated?)}))))

(defn evidence
  "`GET /audit/evidence/:subjectId` — every state change of one subject, in
  order, with its actor (PR-074, AC-12).

  The pack states its own boundaries: the period it spans and whether it hit
  the row cap. An auditor should never have to infer completeness from the
  absence of a warning.

  A subject with no events is `404` rather than an empty pack. An evidence pack
  that is silently empty is worse than none at all, because it reads as proof
  that nothing happened."
  [pool]
  (fn [request]
    (let [[_ organisation-id] (principal/for-request pool request :audit/read)
          subject-id (wire/read-uuid (get-in request [:path-params :subjectId]) "subjectId")
          pack (audit-store/evidence-pack pool organisation-id subject-id)]
      (when-not pack
        (err/not-found! "No audit events for this subject in this organisation"
                        {:subject-id (str subject-id)}))
      (resp/ok {"subjectId"   (str (:subject-id pack))
                "subjectType" (:subject-type pack)
                "from"        (str (:from pack))
                "to"          (str (:to pack))
                "events"      (mapv wire/audit-event->wire (:events pack))
                "count"       (count (:events pack))
                "limit"       audit-store/row-cap
                "truncated"   (boolean (:truncated? pack))}))))
