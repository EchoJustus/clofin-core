(ns clofin.screening.subject
  "The screening subject: what a screening decision is taken *over*, as a
  digest.

  A decision that names no content cannot be reproduced and cannot be held to
  the instruction it was made about. So every screening result and every case
  is bound to `digest` of the instruction under the row lock, and the gate in
  `clofin.payments.repository/transition!` compares the digest of the row it
  has locked with the digest a decision names (C-07, ADR-0028 D5).

  ## What is in the projection, and what is deliberately not

  Identity and the screened content — the instruction's id and organisation,
  the debtor account, the three beneficiary fields a rule can read, the amount,
  the value date and the purpose code. Everything an amendment can change.

  **Not** status, provenance or timestamps. A submission moves the status and
  must not change the digest — otherwise every decision would be moot the
  instant it permitted the transition it was taken for. An amendment changes
  the content, and must: a disposition given on one beneficiary does not clear
  another.

  The `:projection` member names the projection's version. A changed
  projection — a field added, a field removed — changes every digest, which is
  the honest outcome: a decision taken over one projection is not a decision
  over another.

  Rendered on `PaymentInstruction` as `screeningDigest`, so a client can echo
  the digest core holds rather than reimplement the canonical form.

  Pure: no database, no clock, no identifier generation."
  (:require [clofin.audit :as audit]
            [clofin.idempotency :as idem]))

(def projection-version
  "The projection's name and version, carried inside it."
  "screening-subject/1")

(defn projection
  "The fields of `instruction` a screening digest covers.

  Built key by key rather than with `select-keys`, so an instruction whose map
  omits `:creditor-country` and one that carries it as nil project — and digest
  — identically: absent and nil are one fact about an optional field."
  [instruction]
  {:projection        projection-version
   :id                (:id instruction)
   :organisation-id   (:organisation-id instruction)
   :debtor-account-id (:debtor-account-id instruction)
   :creditor-name     (:creditor-name instruction)
   :creditor-account  (:creditor-account instruction)
   :creditor-country  (:creditor-country instruction)
   :amount            (:amount instruction)
   :value-date        (:value-date instruction)
   :purpose-code      (:purpose-code instruction)})

(defn digest
  "Lowercase SHA-256 hex over `clofin.idempotency/canonical` of
  `clofin.audit/normalise` applied to `projection`.

  No version prefix, unlike an audit digest: the version is *inside* the
  projection, and the contract publishes the value as 64 hex characters
  (`ScreeningResultRequest.instructionDigest`, `^[0-9a-f]{64}$`)."
  [instruction]
  (idem/digest (audit/normalise (projection instruction))))
