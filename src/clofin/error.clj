(ns clofin.error
  "Domain error vocabulary.

  Domain code signals failure by throwing an `ex-info` carrying `:clofin/error`
  in its `ex-data`. The HTTP layer is the only place that knows how to turn one
  of these into a status code, which keeps the domain free of transport
  concerns (see docs/ADR/0010-thin-ring-compatible-http-adapter.md).

  Anything thrown without `:clofin/error` is a defect, not a domain outcome, and
  is reported as an internal error with a correlation id and no detail.")

(def error-types
  "The complete set of domain error categories, and how the HTTP layer renders
  each one. Adding a category means deciding its externally visible meaning.

  `:problem-type` is optional and names the problem *type* a category is
  reported under, when that differs from the category's own name. Only
  `:field-validation` uses it — see below."
  {:validation   {:status 400 :title "Request failed validation"}
   :unauthorised {:status 401 :title "Authentication required"}
   :forbidden    {:status 403 :title "Not permitted"}
   :not-found    {:status 404 :title "Resource not found"}
   :conflict     {:status 409 :title "Conflicting state"}
   :unprocessable {:status 422 :title "Request cannot be processed"}
   :unavailable  {:status 503 :title "Dependency unavailable"}

   ;; A request that was understood, and whose named fields are each rejected on
   ;; their own merits: an amount of zero, a value date in the past, a purpose
   ;; code outside the vocabulary. `:validation` above keeps 400 for a request
   ;; that could not be *understood* — a malformed UUID, an absent body.
   ;;
   ;; The distinction is ADR-0012's: 400 is a bug in the caller, 422 is a
   ;; business outcome to show a human. Both report the same problem type,
   ;; because to a client branching on `type` they are one class of failure and
   ;; `status` already separates them. See ADR-0014.
   :field-validation {:status 422
                      :title "Request failed validation"
                      :problem-type :validation}})

(def internal-key-namespace
  "The `ex-data` key namespace that marks a field **internal**: logged, never
  rendered to a caller.

  `:clofin/error` and `:clofin/message` were always stripped by
  `clofin.http.response/error->problem`, which then published *everything else*
  — with no allowlist behind a docstring that claimed only \"explicitly-declared
  public data\" reached the caller. Several repositories attach the PostgreSQL
  constraint name that refused a statement, so `errors.constraint` published
  schema identifiers like `settlement_item_instruction_key` to anyone who could
  provoke a conflict, contradicting C-11 (audit finding **A-009**).

  Generalising the existing two-key exception into a rule was preferred to
  listing the public fields: an allowlist has to be extended every time a
  handler adds an error field, and the extension is where the next
  `:constraint` gets waved through. Under this rule the *default* is public —
  which is correct, because most of what reaches `ex-data` is written for the
  caller — and anything a repository attaches for diagnosis says so in the key
  it chooses. `internal` is the whole vocabulary a call site needs to know."
  "clofin")

(defn internal
  "Mark diagnostic data as internal. `(err/internal {:constraint \"x_key\"})`
  becomes `{:clofin/constraint \"x_key\"}`.

  Merge it into an error's data alongside the public fields; the two travel
  together to the log and are separated at the boundary."
  [data]
  (into {} (map (fn [[k v]] [(keyword internal-key-namespace (name k)) v])) data))

(defn- internal-key?
  "True when `k` is marked internal.

  Guarded on `keyword?` because `ex-data` keys are not always keywords: a
  field-validation error is keyed by the **wire member name** a caller sent, as
  a string, so that a rejection reads back in the caller's own vocabulary
  (`clofin.api.payments`). A string key is caller-facing by construction — a
  caller wrote it — so it is public, and asking `namespace` about one throws."
  [k]
  (and (keyword? k) (= internal-key-namespace (namespace k))))

(defn public-data
  "The subset of `ex-data` that may be rendered to a caller.

  Everything whose key is not in the `clofin` namespace. Applied by
  `clofin.http.response/error->problem`; kept here beside the rule it
  implements so the two cannot drift."
  [data]
  (into {} (remove (comp internal-key? key)) data))

(defn internal-data
  "The subset of `ex-data` that is for the log and not for the caller, with the
  marker namespace stripped so a log line reads as it was written."
  [data]
  (into {}
        (keep (fn [[k v]] (when (internal-key? k) [(keyword (name k)) v])))
        (dissoc data :clofin/error :clofin/message)))

(defn error
  "Build a domain error. `type` must be a key of `error-types`."
  ([type message] (error type message {}))
  ([type message data]
   {:pre [(contains? error-types type)]}
   (ex-info message (assoc data :clofin/error type :clofin/message message))))

(defn domain-error?
  "True when `t` is a domain error rather than an unexpected defect."
  [t]
  (boolean (and (instance? clojure.lang.IExceptionInfo t)
                (contains? error-types (:clofin/error (ex-data t))))))

(defn fail!
  "Throw a domain error."
  ([type message] (throw (error type message)))
  ([type message data] (throw (error type message data))))

(defn invalid!
  "Throw a validation error. The most common domain failure by far."
  ([message] (fail! :validation message))
  ([message data] (fail! :validation message data)))

(defn not-found!
  ([message] (fail! :not-found message))
  ([message data] (fail! :not-found message data)))

(defn conflict!
  ([message] (fail! :conflict message))
  ([message data] (fail! :conflict message data)))

(defn forbidden!
  ([message] (fail! :forbidden message))
  ([message data] (fail! :forbidden message data)))
