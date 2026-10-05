(ns clofin.screening.decision
  "C-07's judgement: may this instruction be submitted, on what core has
  decided about it?

  **Core's decision, and only core's.** `decide` reads the latest result whose
  origin is `core`, taken over the instruction's *current* digest. A client's
  result — accepted or refused — is a row the trail keeps and this function
  never sees: an accepted client `clear` does not satisfy the gate, because
  *no instruction can be released without a completed screening decision*
  means core's decision (ADR-0028 D5, and its *Alternatives considered*:
  \"Core trusts a client's `:clear` and transitions on it — inverts C-07\").

  Two callers ask, deliberately: `clofin.screening.service/submit-screened!`,
  which screens and then asks, and `clofin.payments.repository/transition!`,
  which re-reads under the row lock and asks again. The second is what makes
  the gate a state-machine precondition rather than a property of one service:
  a direct call of `transition!` with `:submit` and no decision is refused by
  the repository itself.

  Also the home of the stored vocabularies of results and cases, each compared
  with its check constraint in migration `0015` by `clofin.db.vocabulary-test`.

  Pure: no database, no clock, no identifier generation.")

;; ---------------------------------------------------------------------------
;; Vocabularies
;; ---------------------------------------------------------------------------

(def outcomes
  "What a screening result says. `screening_result_outcome_known` and
  `screening_result_core_outcome_known`."
  (sorted-set :clear :hit))

(def origins
  "Who produced a result: core at a submission, or a client as evidence.
  `screening_result_origin_known`."
  (sorted-set :core :client))

(def result-dispositions
  "What core did with a result. `accepted`: reproduced (every core result is).
  `refused`: core's recomputation disagreed, and the row is kept as evidence of
  that rather than rolled back with its refusal (lesson L-11).
  `screening_result_disposition_known`."
  (sorted-set :accepted :refused))

(def stored-refusal-reasons
  "Why a stored result was refused. One reason: the only refusal that leaves a
  row is the one that is itself evidence. `screening_result_refusal_reason_known`
  — a one-value `IN`, which PostgreSQL renders as `=`, so a test of its own
  compares it rather than the vocabulary discovery."
  (sorted-set "screening-result-mismatch"))

(def match-sides
  "Which side of a result a matched entry is on: what the client `submitted`,
  or what `core` recomputed. `screening_result_match_side_known`."
  (sorted-set :submitted :core))

(def case-statuses
  "A case is open, or dispositioned — once, finally. `screening_case_status_known`."
  (sorted-set :open :dispositioned))

(def case-dispositions
  "What compliance decided about a hit. `screening_case_disposition_known`."
  (sorted-set :false-positive :confirmed-hit))

(def case-events
  "What may be done to a case in each status, rendered as `permittedTransitions`
  so a client need not hold a copy of the rule."
  {:open          (sorted-set :disposition)
   :dispositioned (sorted-set)})

;; ---------------------------------------------------------------------------
;; The decision
;; ---------------------------------------------------------------------------

(def decisions
  "Every answer `decide` gives."
  (sorted-set :permit :refuse/no-decision :refuse/hit
              :refuse/confirmed-hit :refuse/list-retired))

(defn- kw
  "A stored value as a keyword — rows carry strings, values built in memory
  carry keywords — and nil as nil."
  [x]
  (when (some? x) (keyword (name x))))

(defn- core-decision-for?
  "True when `result` is core's own accepted decision over `digest`."
  [result digest]
  (and (some? result)
       (= :core (kw (:origin result)))
       (= :accepted (kw (:disposition result)))
       (= digest (:instruction-digest result))))

(defn- case-for?
  "True when `case` is about the same instruction content and list as `result`.

  A disposition is bound to the digest and list version its case names: an
  amendment or a new list makes it moot. Checked here as well as in the query
  that finds the case, so a caller that handed the wrong case is refused
  rather than believed."
  [case result]
  (and (some? case)
       (= (:instruction-digest case) (:instruction-digest result))
       (= (:list-version case) (:list-version result))))

(defn decide
  "Judge core's latest result for an instruction's current digest.

  `{:digest d :latest-core-result r :case c}` →

  | In order | Answer |
  |---|---|
  | no core result for digest `d` | `:refuse/no-decision` |
  | the result's list is retired (`:list-retired?`) | `:refuse/list-retired` |
  | outcome `clear` | `:permit` |
  | outcome `hit`, the latest case for (instruction, `d`, list) dispositioned `false-positive` | `:permit` |
  | … dispositioned `confirmed-hit` | `:refuse/confirmed-hit` |
  | otherwise — no case, or an open one | `:refuse/hit` |

  `r` is accepted as nil, as a client result, or as a core result for another
  digest, and each of those is `:refuse/no-decision`: the caller's query is
  not trusted to have filtered correctly, which is the posture that makes this
  a second enforcement point rather than a restatement of the first."
  [{:keys [digest latest-core-result case]}]
  (let [r latest-core-result]
    (cond
      (not (core-decision-for? r digest))  :refuse/no-decision
      (:list-retired? r)                   :refuse/list-retired
      (= :clear (kw (:outcome r)))       :permit
      :else
      (let [c (when (case-for? case r) case)]
        (cond
          (and c (= :dispositioned (kw (:status c)))
               (= :false-positive (kw (:disposition c))))
          :permit

          (and c (= :dispositioned (kw (:status c)))
               (= :confirmed-hit (kw (:disposition c))))
          :refuse/confirmed-hit

          :else :refuse/hit)))))

(defn permits-submit?
  "True when `decision` lets `submit` proceed."
  [decision]
  (= :permit decision))

(defn refusal-reason
  "The `errors.reason` a refused decision is reported under.

  `screening-required` when core holds no usable decision — none for this
  content, or one against a retired list; `screening-hit` when it holds one and
  the hit stands. Nil for `:permit`."
  [decision]
  (case decision
    :permit nil
    (:refuse/no-decision :refuse/list-retired) "screening-required"
    (:refuse/hit :refuse/confirmed-hit)        "screening-hit"))
