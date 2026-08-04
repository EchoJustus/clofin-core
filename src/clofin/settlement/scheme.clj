(ns clofin.settlement.scheme
  "The settlement scheme adapter: a protocol, and the one **simulated**
  implementation behind it.

  > CloFin is not connected to any bank, payment scheme or central bank and
  > holds no regulatory authorisation. Nothing in this namespace talks to
  > anything. `SimulatedScheme` computes an outcome from an attribute of the
  > instruction and returns it; there is no socket, no file drop and no
  > correspondent.

  The protocol exists so the seam is visible rather than implied
  (`ARCHITECTURE.md` §2: every external box is a simulated adapter behind a
  Clojure protocol — the protocol is the contract, the simulator is one
  implementation). Naming the seam is what keeps a future real adapter from
  being bolted onto the side of a handler.

  ## What the simulation decides, and how to predict it

  Outcomes derive from the **last digit of the instruction's creditor account**,
  which in CloFin is always a synthetic `SG-SYNTH-…` reference:

  | Last digit | Outcome |
  |---|---|
  | `0`–`6` | settled |
  | `7`, `8` | returned, reason `SIM-RETURN` |
  | `9` | **no response at all** — the item stays pending and the timeout sweep will find it |

  Chosen over a random seed for three reasons. A reviewer running the UAT script
  can *choose* the outcome by choosing a creditor account, so partial failure is
  producible on demand rather than hoped for. A test asserts a value rather than
  a distribution. And keying the simulation off a field that already says
  `SYNTH` in every row keeps the artificiality visible at the point of use —
  an outcome derived from the *amount* would read, at a glance, like a rule
  about money.

  The rule is stated here, in the OpenAPI description of
  `recordSchemeResponse`, and in the UAT script, because a simulation whose
  behaviour is only discoverable by reading its source is a simulation a
  reviewer cannot review.

  ## What the adapter does not do

  It does not write. Responses reach CloFin through
  `POST /settlement-batches/{id}/scheme-responses` — the injection point — and
  are recorded by `clofin.settlement.service`. This namespace only *decides what
  a simulated scheme would say*, so a test, a UAT reviewer and a future real
  adapter all meet the system at the same door.

  Pure: no database, no clock, no identifier generation."
  (:require [clofin.error :as err]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The seam
;; ---------------------------------------------------------------------------

(defprotocol SchemeAdapter
  "What CloFin needs of a settlement scheme.

  Deliberately small. A real adapter would add transport, credentials and a
  cut-off calendar; none of those is expressible as a simulation, and inventing
  them here would produce interfaces shaped by guesses rather than by a
  connection."
  (scheme-id [this]
    "The scheme this adapter settles for — one of `clofin.settlement.batch/schemes`.")
  (submit-reference [this batch]
    "The reference the scheme would return on accepting a batch. Deterministic
     from the batch, so a replay of the same submission carries the same
     reference and the `scheme_response` replay key sees it as one delivery.")
  (responses-for [this batch instructions]
    "What this scheme would eventually say about each instruction, as
     `[{:instruction-id … :kind … :reference … :reason …} …]`.

     An instruction the scheme never answers about produces **no entry** — that
     absence is the whole timeout case, and returning a `:kind \"timeout\"`
     would be a response, which is precisely what a timeout is not."))

;; ---------------------------------------------------------------------------
;; The simulation
;; ---------------------------------------------------------------------------

(def ^:private settled-digits #{\0 \1 \2 \3 \4 \5 \6})
(def ^:private returned-digits #{\7 \8})
;; \9 is deliberately absent from both: no response at all.

(def return-reason
  "The reason a simulated return carries.

  Prefixed `SIM-` for the same reason the scheme names are: a return reason is
  free text that ends up in an exception queue a human reads, and one that could
  be mistaken for a real scheme's reason code is a synthetic record reading as
  a real one."
  "SIM-RETURN: simulated scheme return")

(defn outcome-for
  "The outcome the simulation gives an instruction: `:settled`, `:returned`, or
  `:no-response`.

  Exposed as a plain function — not only through the protocol — because a test
  and the UAT script both want to state the expected outcome without
  constructing an adapter, and because a rule a reviewer has to instantiate an
  object to read is a rule nobody checks."
  [instruction]
  (let [account (str (:creditor-account instruction))
        digit   (last account)]
    (when (str/blank? account)
      (err/invalid! "The simulated scheme derives its outcome from the creditor account, and this instruction has none"
                    {:instruction-id (str (:id instruction))}))
    (cond
      (contains? settled-digits digit)  :settled
      (contains? returned-digits digit) :returned
      ;; Every other character, `9` included. A creditor account not ending in a
      ;; digit is treated as no-response rather than defaulted to settled: the
      ;; safe simulated outcome for "this rule does not know" is the one that
      ;; leaves the money's fate unknown, not the one that says it arrived.
      :else                             :no-response)))

(defrecord SimulatedScheme [id]
  SchemeAdapter
  (scheme-id [_] id)

  (submit-reference [_ batch]
    ;; Derived from the batch id rather than generated, so that submitting the
    ;; same batch twice produces the same reference — which is what lets the
    ;; `scheme_response` replay key recognise the second delivery as a duplicate
    ;; instead of admitting it as a new ack.
    (str "SIM-ACK-" (:id batch)))

  (responses-for [_ batch instructions]
    (into []
          (keep (fn [instruction]
                  (case (outcome-for instruction)
                    :settled  {:instruction-id (:id instruction)
                               :kind           "settled"
                               :reference      (str "SIM-STL-" (:id batch) "-" (:id instruction))}
                    :returned {:instruction-id (:id instruction)
                               :kind           "returned"
                               :reason         return-reason
                               :reference      (str "SIM-RTN-" (:id batch) "-" (:id instruction))}
                    ;; No entry. The absence *is* the timeout.
                    :no-response nil)))
          instructions)))

(defn simulated
  "The simulated adapter for `scheme-id`.

  There is exactly one implementation and it is this one. A registry keyed by
  scheme name would suggest a choice of adapters exists; it does not, and the
  day a real one arrives it will need a connection, credentials and a brief."
  [scheme]
  (->SimulatedScheme scheme))
