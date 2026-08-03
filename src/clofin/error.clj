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
