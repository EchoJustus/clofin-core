(ns clofin.screening.repository
  "Persistence for screening lists, results and cases (migration `0015`).

  The seam ADR-0012 names: this namespace may require `clofin.db.*`, and the
  pure namespaces beside it — `rules`, `subject`, `decision`, `list` — may not.

  **Lists are read here and written nowhere in `src/`.** Loading and retiring a
  version is `clofin.tools.screening-list`, on `tools/`, which the running
  service does not have on its classpath (C-07: a client that could load the
  list it is screened against would make the control unenforceable).

  Results and cases are written on the caller's transaction, always after
  `clofin.payments.repository/lock-instruction!` has locked the instruction
  they are about (lesson L-8): the digest they are bound to is a digest of a row
  no other transaction can be changing.

  ## Lock order

  Unchanged from `clofin.payments.repository`'s, extended by one row type and
  one advisory lock: `payment_instruction` first, then the list lock
  (`clofin.screening.list/lock-key`, shared), then `screening_case`. A
  disposition locks the instruction before the case for that reason, so a
  disposition and a submission of the same instruction serialise rather than
  interleave. The loading tool takes the table lock on `screening_list`, then
  the list lock exclusive, and touches no instruction or case, so no order it
  takes can meet a decision's in reverse."
  (:require [clofin.db.core :as db]
            [clofin.screening.list :as screening-list]))

(def row-cap
  "Maximum rows a list query returns, with `truncated` saying whether there were
  more — the same cap and reasoning as every other list (ADR-0011)."
  500)

(defn- kw [x] (when (some? x) (keyword x)))

;; ---------------------------------------------------------------------------
;; Lists
;; ---------------------------------------------------------------------------

(defn- row->list-summary
  [row]
  {:version     (:version row)
   :source      (:source row)
   :loaded-at   (db/->instant (:loaded-at row))
   :retired-at  (some-> (:retired-at row) db/->instant)
   :entry-count (some-> (:entry-count row) db/->long)})

(defn- entries-of
  "The entries of one list version with their rules, entries by id and rules
  by position — the list as it was loaded."
  [source version]
  (let [rows (db/query source ["select e.id as entry_id, r.position, r.field, r.operator, r.value
                                  from screening_entry e
                                  join screening_rule r
                                    on r.list_version = e.list_version and r.entry_id = e.id
                                 where e.list_version = ?
                                 order by e.id, r.position"
                                version])]
    (->> rows
         (partition-by :entry-id)
         (mapv (fn [rs]
                 {:id    (:entry-id (first rs))
                  :rules (mapv (fn [r] {:field    (keyword (:field r))
                                        :operator (keyword (:operator r))
                                        :value    (:value r)})
                               rs)})))))

(defn list-lists
  "Every list version loaded, most recently loaded first."
  [source]
  (mapv row->list-summary
        (db/query source ["select version, source, loaded_at, retired_at, entry_count
                             from screening_list
                            order by loaded_at desc, version"])))

(defn find-list
  "The list version `version` with every entry and rule, or nil."
  [source version]
  (when-let [row (db/query-one source ["select version, source, loaded_at, retired_at, entry_count
                                          from screening_list where version = ?"
                                        version])]
    (assoc (row->list-summary row) :entries (entries-of source version))))

(defn accepted-list
  "The one accepted — not retired — list version with its entries, or nil when
  none is accepted.

  **Two accepted versions is a defect, not a choice.** The loading tool keeps
  one accepted under a table lock, but the schema does not forbid two (see
  `clofin.tools.screening-list`); if two are ever found, screening against
  either would be a decision nobody can say was taken against *the* list, so
  this throws — a `500` with a correlation id — rather than picking one."
  [source]
  (let [rows (db/query source ["select version, source, loaded_at, retired_at, entry_count
                                  from screening_list where retired_at is null
                                 order by version"])]
    (when (> (count rows) 1)
      (throw (ex-info "More than one screening list is accepted; screening refuses to choose"
                      {:accepted (mapv :version rows)})))
    (when-let [row (first rows)]
      (assoc (row->list-summary row) :entries (entries-of source (:version row))))))

(defn hold-list-lock!
  "Hold the list lock (`clofin.screening.list/lock-key`) **shared** until the
  caller's transaction ends. Taken by every screening decision, after the
  instruction's lock (L-8, 017-REQ R-7); re-taking it in one transaction is
  immediate."
  [tx]
  (db/query-one tx ["select pg_advisory_xact_lock_shared(?) /* screening list lock */"
                    screening-list/lock-key]))

(defn lock-accepted-list!
  "`accepted-list`, read under the list lock held shared (`hold-list-lock!`).

  The lock is taken in a statement of its own, so the read that follows has a
  snapshot taken after any list change that was in flight has committed: a
  decision sees the old list or the new one, never a retired list as accepted
  and never no list while a replacement was being committed. A list change
  waits for every decision holding the lock, so no decision commits against a
  list after its retirement did, and no result is recorded after its list's
  `retired_at` (the tool stamps it after its wait)."
  [tx]
  (hold-list-lock! tx)
  (accepted-list tx))

;; ---------------------------------------------------------------------------
;; Results
;; ---------------------------------------------------------------------------

(def ^:private result-columns
  "Every column of a result, its matched entries on both sides, the case it
  opened, and the event that recorded it — one read, so a result is never
  rendered without the evidence that it was recorded."
  "select r.id, r.organisation_id, r.instruction_id, r.list_version, r.origin, r.outcome,
          r.core_outcome, r.agrees, r.instruction_digest, r.disposition,
          r.disposition_reason, r.recorded_by, r.recorded_at, r.screened_at,
          (select coalesce(array_agg(m.entry_id order by m.entry_id), '{}'::text[])
             from screening_result_match m
            where m.result_id = r.id and m.side = 'submitted') as submitted_entries,
          (select coalesce(array_agg(m.entry_id order by m.entry_id), '{}'::text[])
             from screening_result_match m
            where m.result_id = r.id and m.side = 'core') as core_entries,
          (select c.id from screening_case c where c.result_id = r.id) as case_id,
          (select a.id from audit_event a
            where a.subject_id = r.id and a.action = 'screening-result.recorded'
              and a.organisation_id = r.organisation_id
            order by a.occurred_at, a.id limit 1) as audit_event_id
     from screening_result r ")

(defn- text-array
  [v]
  (cond
    (nil? v) []
    (instance? java.sql.Array v) (vec (.getArray ^java.sql.Array v))
    :else (vec v)))

(defn- row->result
  [row]
  (when row
    (let [origin (keyword (:origin row))
          core-entries (text-array (:core-entries row))]
      {:id                   (:id row)
       :organisation-id      (:organisation-id row)
       :instruction-id       (:instruction-id row)
       :list-version         (:list-version row)
       :origin               origin
       :outcome              (keyword (:outcome row))
       :core-outcome         (keyword (:core-outcome row))
       :agrees               (boolean (:agrees row))
       :instruction-digest   (:instruction-digest row)
       :disposition          (keyword (:disposition row))
       :disposition-reason   (:disposition-reason row)
       :recorded-by          (:recorded-by row)
       :recorded-at          (db/->instant (:recorded-at row))
       :screened-at          (some-> (:screened-at row) db/->instant)
       ;; A core result *is* core's outcome, so the entries it matched are the
       ;; core side; a client result's own claim is the submitted side.
       :matched-entries      (if (= :core origin) core-entries
                                 (text-array (:submitted-entries row)))
       :core-matched-entries core-entries
       :case-id              (:case-id row)
       :audit-event-id       (:audit-event-id row)})))

(defn insert-result!
  "Write a result and its matched entries on the caller's transaction. Returns
  it as stored.

  `recorded_at` is written as `clock_timestamp()` — the moment of the insert —
  rather than the column default `now()`, which is the transaction's *start*.
  Results for one instruction are written under its row lock, so the insert
  instant orders them as they were decided; a transaction's start does not,
  when a transaction that began earlier waited longer for the lock. \"The
  latest core result\", which the gate reads, has to mean the latest decided."
  [tx {:keys [id organisation-id instruction-id list-version origin outcome core-outcome
              agrees instruction-digest disposition disposition-reason recorded-by
              screened-at submitted-entries core-entries]}]
  (db/execute! tx ["insert into screening_result
                      (id, organisation_id, instruction_id, list_version, origin, outcome,
                       core_outcome, agrees, instruction_digest, disposition, disposition_reason,
                       recorded_by, recorded_at, screened_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)"
                   id organisation-id instruction-id list-version (name origin) (name outcome)
                   (name core-outcome) (boolean agrees) instruction-digest (name disposition)
                   disposition-reason recorded-by screened-at])
  (doseq [[side entries] [["submitted" submitted-entries] ["core" core-entries]]
          entry-id (distinct entries)]
    (db/execute! tx ["insert into screening_result_match (result_id, side, list_version, entry_id)
                      values (?, ?, ?, ?)"
                     id side list-version entry-id]))
  (row->result (db/query-one tx [(str result-columns "where r.id = ?") id])))

(defn find-result
  "One result, re-read with its matches, case and audit event."
  [source organisation-id id]
  (row->result (db/query-one source [(str result-columns "where r.organisation_id = ? and r.id = ?")
                                     organisation-id id])))

(defn list-results
  "An instruction's results, most recently recorded first, capped at `row-cap`.
  `{:results [...] :truncated? bool}`."
  [source organisation-id instruction-id]
  (let [rows (db/query source [(str result-columns
                                    "where r.organisation_id = ? and r.instruction_id = ?
                                     order by r.recorded_at desc, r.id limit ?")
                               organisation-id instruction-id (inc row-cap)])]
    {:results    (mapv row->result (take row-cap rows))
     :truncated? (> (count rows) row-cap)}))

(defn latest-accepted-core-results
  "For each instruction id, the latest accepted core result for its **current**
  digest, as `{instruction-id result}` — the approval queue's `screening`
  member (PR-015). `digests` is `{instruction-id digest}`, computed by the
  caller from the rows it is rendering."
  [source organisation-id digests]
  (if (empty? digests)
    {}
    (let [ids (vec (keys digests))
          rows (db/query source
                         (into [(str "select distinct on (r.instruction_id) r.id, r.instruction_id,
                                         r.instruction_digest, r.outcome, r.list_version, r.recorded_at
                                        from screening_result r
                                       where r.organisation_id = ?
                                         and r.origin = 'core' and r.disposition = 'accepted'
                                         and r.instruction_id in (" (db/placeholders (count ids)) ")
                                       order by r.instruction_id, r.recorded_at desc, r.id desc")
                                organisation-id]
                               ids))]
      ;; `distinct on` picks the latest per instruction; it must also be over
      ;; the *current* digest, or the queue would show a decision about
      ;; content the instruction no longer has.
      (into {}
            (keep (fn [row]
                    (when (= (:instruction-digest row) (get digests (:instruction-id row)))
                      [(:instruction-id row)
                       {:id           (:id row)
                        :outcome      (keyword (:outcome row))
                        :list-version (:list-version row)
                        :recorded-at  (db/->instant (:recorded-at row))}])))
            rows))))

;; ---------------------------------------------------------------------------
;; Cases
;; ---------------------------------------------------------------------------

(def ^:private case-columns
  "select id, organisation_id, instruction_id, result_id, instruction_digest, list_version,
          status, disposition, rationale, dispositioned_by, opened_at, dispositioned_at
     from screening_case ")

(defn- row->case
  [row]
  (when row
    {:id                 (:id row)
     :organisation-id    (:organisation-id row)
     :instruction-id     (:instruction-id row)
     :result-id          (:result-id row)
     :instruction-digest (:instruction-digest row)
     :list-version       (:list-version row)
     :status             (keyword (:status row))
     :disposition        (kw (:disposition row))
     :rationale          (:rationale row)
     :dispositioned-by   (:dispositioned-by row)
     :opened-at          (db/->instant (:opened-at row))
     :dispositioned-at   (some-> (:dispositioned-at row) db/->instant)}))

(defn find-case
  "The case with this id within this organisation, or nil."
  [source organisation-id id]
  (row->case (db/query-one source [(str case-columns "where organisation_id = ? and id = ?")
                                   organisation-id id])))

(defn lock-case!
  "A case read `for update`, or nil. Taken after the instruction's lock."
  [tx organisation-id id]
  (row->case (db/query-one tx [(str case-columns "where organisation_id = ? and id = ? for update")
                               organisation-id id])))

(defn open-case-for
  "The open case on an instruction, or nil. At most one, by `screening_case_open_key`."
  [source instruction-id]
  (row->case (db/query-one source [(str case-columns "where instruction_id = ? and status = 'open'")
                                   instruction-id])))

(defn latest-case-for
  "The latest case for (instruction, digest, list version), or nil — the case
  a decision over that content against that list consults."
  [source instruction-id digest list-version]
  (row->case (db/query-one source [(str case-columns
                                        "where instruction_id = ? and instruction_digest = ?
                                           and list_version = ?
                                         order by opened_at desc, id desc limit 1")
                                   instruction-id digest list-version])))

(defn open-case!
  "Open a case on `result`'s hit **if none is open on the instruction**.
  Returns `{:case … :opened? bool}`.

  `screening_case_open_key` is the arbiter, not a read: the insert is
  `on conflict … do nothing`, so a concurrent opener that is not holding the
  instruction's lock still cannot make a second open case, and the loser reads
  the winner's. `opened_at` is the transaction's time, as for every other row
  created with its event."
  [tx {:keys [id organisation-id instruction-id result-id instruction-digest list-version]}]
  (let [inserted (db/query-one tx ["insert into screening_case
                                      (id, organisation_id, instruction_id, result_id,
                                       instruction_digest, list_version)
                                    values (?, ?, ?, ?, ?, ?)
                                    on conflict (instruction_id) where status = 'open' do nothing
                                    returning id"
                                   id organisation-id instruction-id result-id
                                   instruction-digest list-version])]
    (if inserted
      {:case (find-case tx organisation-id id) :opened? true}
      {:case (or (open-case-for tx instruction-id)
                 (throw (ex-info "A screening case conflicted with an open case that cannot be read"
                                 {:instruction-id (str instruction-id)})))
       :opened? false})))

(defn disposition-case!
  "Record the disposition of an open case on the caller's transaction. Returns
  the case as stored. The trigger refuses this on a dispositioned case, as the
  second enforcement behind the service's `409`."
  [tx {:keys [id disposition rationale dispositioned-by]}]
  (db/execute! tx ["update screening_case
                       set status = 'dispositioned', disposition = ?, rationale = ?,
                           dispositioned_by = ?, dispositioned_at = now()
                     where id = ? and status = 'open'"
                   (name disposition) rationale dispositioned-by id])
  (row->case (db/query-one tx [(str case-columns "where id = ?") id])))

(defn list-cases
  "An organisation's cases, most recently opened first, capped at `row-cap`;
  `status` narrows to one. `{:cases [...] :truncated? bool}`."
  [source organisation-id {:keys [status]}]
  (let [rows (db/query source
                       (if status
                         [(str case-columns "where organisation_id = ? and status = ?
                                              order by opened_at desc, id limit ?")
                          organisation-id (name status) (inc row-cap)]
                         [(str case-columns "where organisation_id = ?
                                              order by opened_at desc, id limit ?")
                          organisation-id (inc row-cap)]))]
    {:cases      (mapv row->case (take row-cap rows))
     :truncated? (> (count rows) row-cap)}))

