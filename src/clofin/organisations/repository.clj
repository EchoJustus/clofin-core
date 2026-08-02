(ns clofin.organisations.repository
  "Persistence for organisations.

  A `repository` namespace is CloFin's persistence seam: it may require
  `clofin.db.*`, and the pure domain namespaces beside it may not. See
  docs/ADR/0012-repository-seam-and-posting-time-validation.md.

  Every function takes a `source` — a pool or a connection already inside a
  caller's transaction — so the same function composes into a larger unit of
  work without knowing which it was given."
  (:require [clofin.db.core :as db]
            [clofin.error :as err]
            [clofin.organisations.organisation :as organisation]))

(defn- row->organisation
  [row]
  (when row
    {:id         (:id row)
     :legal-name (:legal-name row)
     :short-name (:short-name row)
     :status     (keyword (:status row))}))

(defn create-organisation!
  "Persist a new organisation. Returns it as stored.

  The short name is unique case-insensitively — the schema enforces it on
  `lower(short_name)` — so a collision is a `409`, not a `500`. Two tenants
  distinguishable only by capitalisation would be indistinguishable in every
  export and reconciliation file they appear in."
  [source candidate]
  (let [org (organisation/organisation (merge {:status :active} candidate))]
    (try
      (db/execute! source
                   ["insert into organisation (id, legal_name, short_name, status)
                     values (?, ?, ?, ?)"
                    (:id org) (:legal-name org) (:short-name org) (name (:status org))])
      org
      (catch Exception t
        (let [{:keys [sql-state constraint]} (db/violation t)]
          (if (= (:unique-violation db/sql-states) sql-state)
            (err/conflict! "An organisation with this short name already exists"
                           {:short-name (:short-name org) :constraint constraint})
            (throw t)))))))

(defn find-organisation
  "The organisation with this id, or nil."
  [source id]
  (row->organisation
   (db/query-one source ["select id, legal_name, short_name, status
                          from organisation where id = ?"
                         id])))
