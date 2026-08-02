(ns clofin.organisations.organisation
  "Organisations: the tenant every business record in CloFin belongs to.

  An organisation is a plain map:

      {:id         #uuid \"...\"
       :legal-name \"Meridian Freight Holdings Pte Ltd\"
       :short-name \"meridian\"
       :status     :active}

  All synthetic. No real legal entity is represented anywhere in CloFin.

  Pure: no database, no clock, no identifier generation — the same rule that
  governs `clofin.ledger.*` (ARCHITECTURE.md §4). Persistence lives in
  `clofin.organisations.repository`; see
  docs/ADR/0012-repository-seam-and-posting-time-validation.md."
  (:require [clofin.error :as err]
            [clojure.string :as str]))

(def statuses
  "Lifecycle of an organisation. A closed organisation is retained because the
  journal entries referencing it are retained; it is never deleted."
  {:active    {:description "Trading normally."}
   :suspended {:description "Blocked pending investigation; history remains readable."}
   :closed    {:description "Permanently closed; retained for audit."}})

(def ^:private short-name-pattern
  "Short names appear in URLs, exports and reconciliation filenames, so the
  character set is constrained deliberately rather than left to whatever a
  caller happens to send."
  #"[a-z0-9][a-z0-9-]{1,63}")

(defn organisation
  "Validate and normalise an organisation. Returns it, or throws a validation
  error describing the first problem found."
  [{:keys [id legal-name short-name status] :as candidate}]
  (when-not (uuid? id)
    (err/invalid! "Organisation id must be a UUID" {:id id}))
  (when-not (and (string? legal-name) (not (str/blank? legal-name)))
    (err/invalid! "Organisation legal name is required" {:legal-name legal-name}))
  (when-not (and (string? short-name) (re-matches short-name-pattern short-name))
    (err/invalid! "Organisation short name must be 2–64 lowercase alphanumeric characters or hyphens"
                  {:short-name short-name}))
  (when-not (contains? statuses status)
    (err/invalid! (str "Unknown organisation status: " status)
                  {:status status :known (vec (sort (keys statuses)))}))
  (select-keys candidate [:id :legal-name :short-name :status]))
