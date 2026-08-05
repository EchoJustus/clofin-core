(ns clofin.settlement.response
  "What a scheme response *is*: its semantic identity, its digest, and the
  disposition CloFin reached about it.

  Two audit findings met in this namespace, and it exists because both were
  cases of a fact being implied rather than held.

  ## Receipt and disposition are separate facts (F-008, lesson **L-11**)

  `scheme_response` is evidence that a message arrived. Before migration `0010`
  a message CloFin could not act on was rolled back *by the conflict that
  rejected it*, so the first delivery was unprovable and the identical reference
  could perform work later against changed state. A receipt destroyed by its own
  processing failure is not a receipt.

  So an arrival now carries a **disposition**: a small closed vocabulary saying
  what CloFin did about it. It is stored beside the receipt, in the same
  transaction, and the caller's `409` is rendered *after* that transaction
  commits — see `clofin.settlement.service/record-scheme-response!` and
  `clofin.api.settlement/record-response`.

  ## Replay identity covers every effect-bearing field (F-009, lesson **L-12**)

  The replay key `(batch, instruction, kind, reference)` names a delivery's
  *identity*. It does not say whether two deliveries under that identity are the
  same **message**: for a `timeout-resolution`, `outcome` and `reason` decide
  which way the payment transitions, and they are outside the key. Two
  contradictory answers therefore collapsed into one, and the second was
  reported as an exact replay of a request nobody had sent.

  The fix is the posture `idempotency_key` has taken since ADR-0013 and which
  lesson **L-2** generalised: a key plus a **canonical digest of the complete
  request**. `clofin.idempotency/canonical` is reused unchanged rather than
  reimplemented — one canonical form, one place where \"the same request\" is
  defined. The digest is version-tagged with `clofin.audit/canonicalisation-version`
  for the reason that constant exists: if the canonical form ever changes,
  digests taken before and after must not silently compare as equal.

  Pure: no database, no clock, no identifier generation."
  (:require [clofin.audit :as audit]
            [clofin.error :as err]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Kinds and outcomes
;; ---------------------------------------------------------------------------

(def kinds
  "Every kind of response a simulated scheme may deliver. Identical to
  `scheme_response_kind_known` in migration `0009`; a test asserts the two
  agree."
  (into (sorted-set) ["ack" "settled" "returned" "timeout-resolution"]))

(def response-outcomes
  "Every outcome a response may claim. Identical to
  `scheme_response_outcome_known` in migration `0010`.

  Narrower than `clofin.settlement.batch/item-outcomes`, which also carries
  `timed-out`. That asymmetry is the point: `timed-out` is a fact about CloFin
  having stopped waiting, and no scheme ever *says* it — a response claiming it
  would be a scheme reporting CloFin's own state back to it."
  (into (sorted-set) ["settled" "returned"]))

(def kind-outcome
  "The outcome each kind resolves an item to, where the kind decides it.

  `ack` resolves nothing; `timeout-resolution` carries its outcome in the
  request, because the whole point of a late answer is that it says which of the
  two things happened."
  {"settled"  "settled"
   "returned" "returned"})

;; ---------------------------------------------------------------------------
;; Dispositions
;; ---------------------------------------------------------------------------

(def dispositions
  "What CloFin did about an arrival. Identical to
  `scheme_response_disposition_known` in migration `0010`.

  - `applied` — it resolved an item, transitioned the instruction and posted
    finality.
  - `acknowledged` — a batch-level `ack`. Recorded by design; moves nothing. Its
    own term rather than a flavour of `applied`, because \"did no work\" and
    \"was not supposed to do any\" are different answers to an investigation.
  - `refused` — the message arrived and is kept, and the item was not in a state
    this kind could resolve. No work; the caller received `409` **after** this
    receipt committed."
  (into (sorted-set) ["applied" "acknowledged" "refused"]))

(def refusal-reasons
  "Why a refused arrival was refused, as stable codes with the prose a caller
  is told.

  Codes rather than free text because the replay path reproduces the original
  answer from the stored row: a disposition reason that were prose would be
  prose this code had to parse to answer a replay the same way twice."
  {"item-already-resolved"
   (str "This settlement item already has an outcome; a late answer for a "
        "timed-out item must arrive as a timeout-resolution")
   "item-not-timed-out"
   (str "This settlement item is not timed out, so there is nothing for a "
        "timeout resolution to resolve")
   "item-not-in-batch"
   "This settlement batch has no membership for that payment instruction"})

(defn refusal-detail
  "The prose for a refusal code, or a neutral fallback.

  A fallback rather than a throw: this is read while *reproducing* a stored
  answer, and a receipt written by an earlier version carrying a code this one
  does not know must still replay as the refusal it was, rather than becoming a
  `500` on the evidence path."
  [code]
  (or (refusal-reasons code)
      "This scheme response arrived and was recorded, and CloFin could not act on it"))

(defn refused?  [disposition] (= "refused" disposition))
(defn applied?  [disposition] (= "applied" disposition))

;; ---------------------------------------------------------------------------
;; The semantic request
;; ---------------------------------------------------------------------------

(defn normalise-reason
  "A response's reason as it is stored and digested: trimmed, or nil when blank.

  Blank and absent are the same claim — the scheme gave no reason — and letting
  them digest differently would make `\"\"` and `null` two different messages
  under one reference, which is a `409` a caller could not act on."
  [reason]
  (when (and (string? reason) (not (str/blank? reason)))
    (str/trim reason)))

(defn semantic-request
  "The complete semantic content of one scheme response, as a value.

  **Everything that decides an effect is in here, and nothing else is.**
  `batch-id` and `instruction-id` address the thing the message is about — L-2's
  rule that a canonical digest covers the resource, not only the body, so
  identical bodies about different payments cannot collide. `kind`, `outcome`
  and `reason` decide the transition, the posting and the exception queue entry.

  Deliberately absent: the actor, the correlation id, the entry id and the
  occurrence instant. None changes what the scheme said. A digest covering them
  would make every delivery unique, which would disable the replay guard while
  looking like a stricter version of it."
  [{:keys [batch-id instruction-id kind reference outcome reason]}]
  {:batch-id       batch-id
   :instruction-id instruction-id
   :kind           kind
   :reference      reference
   :outcome        outcome
   :reason         (normalise-reason reason)})

(defn digest
  "The version-tagged canonical digest of a semantic request.

  `clofin.audit/digest` does the work: it normalises domain values — uuids,
  keywords, nils — into something `clofin.idempotency/canonical` can serialise,
  hashes the canonical string, and prefixes the canonicalisation version. Same
  function, same guarantee, same one place to change."
  [request]
  (audit/digest (semantic-request request)))

(defn same-message?
  "True when a stored digest and an incoming one describe the same message.

  Nil-safe on the stored side: rows written before migration `0010` carry no
  digest, and a receipt whose identity cannot be compared must not be *assumed*
  identical — that assumption is precisely what F-009 found. It replays as the
  answer it recorded, and a caller sending something different under that
  reference is told the reference is taken rather than being told it replayed."
  [stored-digest incoming-digest]
  (and (some? stored-digest) (= stored-digest incoming-digest)))

;; ---------------------------------------------------------------------------
;; Shape
;; ---------------------------------------------------------------------------

(defn assert-shape!
  "Validate a response's shape and return it with its outcome and reason
  normalised.

  Runs **before** anything is written, and that ordering is the point. A
  malformed message — a `settled` naming no instruction, a `timeout-resolution`
  naming no outcome — is not a scheme response that arrived and could not be
  acted upon; it is a request that could not be understood, and it is `400`.
  Only a message well-formed enough to *be* a response earns a receipt, which is
  what keeps the receipt table a record of deliveries rather than of typos.

  This is not in tension with F-008. F-008 is about a **processing** conflict —
  the message was fine and the item was not in a state it could resolve — and
  that case commits its receipt, always."
  [{:keys [kind instruction-id outcome] :as request}]
  (when-not (contains? kinds kind)
    (err/invalid! (str "Unknown scheme response kind: " kind)
                  {:kind (str kind) :known (vec kinds)}))
  (when (and (not= "ack" kind) (nil? instruction-id))
    (err/invalid! (str "A '" kind "' scheme response must name the instruction it is about")
                  {:kind kind}))
  (when (and (= "ack" kind) (some? instruction-id))
    (err/invalid! "A batch-level ack is about the batch and must not name an instruction"
                  {:kind kind}))
  (let [resolved (if (= "timeout-resolution" kind)
                   (or outcome
                       (err/invalid!
                        "A timeout resolution must name the outcome it resolves to"
                        {:known (vec response-outcomes)}))
                   (kind-outcome kind))]
    (when (and resolved (not (contains? response-outcomes resolved)))
      (err/invalid! (str "A scheme response cannot claim the outcome " resolved)
                    {:outcome (str resolved) :known (vec response-outcomes)}))
    (assoc request :outcome resolved :reason (normalise-reason (:reason request)))))
