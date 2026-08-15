(ns clofin.http.cors
  "Cross-origin access for browser clients — an allowlist, and nothing else.

  A browser refuses to let a page read a response from another origin unless
  that response says the origin may. Until now no CloFin response has ever said
  so, which means no browser page has ever been able to read one. Making that
  possible is a deliberate exposure decision, recorded in
  docs/ADR/0027-browser-clients-cors-allowlist-and-instance-self-identification.md.

  ## Default-closed, and what that means precisely

  With `CLOFIN_CORS_ALLOWED_ORIGINS` unset or empty, {@link wrap-cors} returns
  **the handler it was given**. Not a wrapper that decides to add nothing — the
  same function object. There is therefore no configuration under which an
  unconfigured service can answer differently from the one that shipped before
  this namespace existed: not a preflight, not a header, not a `Vary`. The
  property is structural rather than tested into place, and the test that
  asserts it compares identity (`identical?`), because equality of behaviour is
  what a bug looks like.

  ## What is never done here

  - **No wildcard.** `*` is refused at start-up with a message naming the
    variable, rather than dropped quietly — an operator who wrote `*` and saw
    the service start would reasonably conclude it had been honoured.
  - **No reflection.** The value emitted in `Access-Control-Allow-Origin` is
    the string from the *configuration*, never the string from the request.
    They are equal by the time it is emitted — that is what matching means —
    but taking it from configuration is what makes \"an arbitrary origin can
    never be echoed\" a property of the code rather than of the comparison
    above it.
  - **No credentials.** `Access-Control-Allow-Credentials` is never sent, so a
    browser will not attach cookies or HTTP authentication to these requests.
    CloFin authenticates by a header the page sets deliberately
    (`clofin.api.principal`), which needs no ambient credential and is safer
    without one.

  ## CORS is not an access control, and this namespace does not pretend otherwise

  A preflight refusal stops a *browser page* from making a non-simple request.
  It stops nothing else: `curl`, a server-side client, or any program that is
  not a browser is unaffected, and even in a browser a **simple** request — a
  `GET` with no custom header — still reaches the handler and executes; only
  the reading of its response is blocked. Nothing in this file protects
  anything. Authorisation is `clofin.api.principal` and the permission each
  handler names, and it is unchanged by anything here."
  (:require [clofin.error :as err]
            [clojure.string :as str]))

(def env-variable
  "The environment variable that configures the allowlist. Named here so the
  refusal messages and the documentation cannot drift from the reader."
  "CLOFIN_CORS_ALLOWED_ORIGINS")

(def allowed-methods
  "The methods a browser page may use, which are the methods the route table
  has — no more.

  `clofin.http.cors-test/allow-methods-are-the-methods-the-route-table-has`
  derives this set from `clofin.routes/routes` and fails if the two diverge, so
  a method added to the API cannot silently become allowed, or silently not be."
  ["GET" "POST" "PATCH" "DELETE"])

(def allowed-request-headers
  "The request headers a browser page may send.

  These are the headers CloFin actually reads, not a defensive superset:
  `content-type` (`clofin.http.middleware`), `idempotency-key`
  (`clofin.api.payments`, `clofin.api.approvals`), `x-actor-id`
  (`clofin.api.principal`) and `x-correlation-id` (`clofin.http.middleware`).
  `clofin.http.cors-test/allow-headers-are-the-headers-the-service-reads`
  discovers that set by walking every `(get-in request [:headers …])` in `src/`
  and fails if this list is not exactly it — because a list of allowed headers
  that was guessed is one that is either too wide or quietly broken, and the
  broken half is invisible outside a browser (standing lesson from 011-REQ §7).

  Lower-case because that is how they are compared; header names are
  case-insensitive and a browser will match this list case-insensitively."
  ["content-type" "idempotency-key" "x-actor-id" "x-correlation-id"])

(def exposed-response-headers
  "The response headers a browser page may read.

  Without this, a page can read only the CORS-safelisted response headers, of
  which `content-type` is the only one CloFin sets. `location` carries the
  identity of a resource that was just created and is otherwise invisible to a
  page that just created it; `x-correlation-id` is the identifier CloFin's own
  error responses tell a caller to quote; `allow` is what a `405` says. A
  client built to display raw responses can then display them.

  Never `*`: a wildcard here would expose response headers this service does
  not set today and might set later, which is a decision nobody would have
  made."
  ["location" "x-correlation-id" "allow"])

(def max-age-seconds
  "How long a browser may cache a preflight answer. Ten minutes: long enough
  that a bootstrap run does not preflight every step, short enough that
  removing an origin from the allowlist takes effect while the operator is
  still watching."
  600)

;; ---------------------------------------------------------------------------
;; Origins
;; ---------------------------------------------------------------------------

(def ^:private origin-pattern
  "An origin is a scheme, a host and an optional port — and nothing else.

  No path, no query, no fragment, no trailing slash, no userinfo. A browser's
  `Origin` header is exactly this production, so anything else in the
  configuration could never match anything and is a mistake worth refusing at
  start-up rather than at three in the morning."
  #"(?i)\A(https?)://([a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*)(?::(\d{1,5}))?\z")

(defn- refuse!
  [value reason]
  (err/invalid! (str env-variable " is not a list of origins: " reason)
                {:variable env-variable :value value}))

(defn read-origin
  "Validate one configured origin, or refuse to start.

  Returns the origin lower-cased, which is the form a browser sends. Scheme and
  host are case-insensitive in a URL, so comparing lower-cased is comparing
  correctly; nothing else about the string is altered."
  [raw]
  (let [value (str/trim (str raw))]
    (when (str/blank? value)
      (refuse! value "an entry is empty"))
    (when (str/includes? value "*")
      ;; Said separately from \"malformed\" because it is not a typo. Someone
      ;; who writes `*` means \"any origin\", and the answer is that this
      ;; service does not have that setting — see the ADR.
      (refuse! value (str "\"" value "\" contains \"*\". There is no wildcard: "
                          "every origin permitted to read a CloFin response is "
                          "named in full, or none is")))
    (let [[_ scheme host port] (re-matches origin-pattern value)]
      (when-not scheme
        (refuse! value (str "\"" value "\" is not an origin. An origin is "
                            "scheme://host with an optional :port — no path, no "
                            "trailing slash, no credentials")))
      (when (and port (not (<= 1 (parse-long port) 65535)))
        (refuse! value (str "\"" value "\" has a port outside 1-65535")))
      (str/lower-case value))))

(defn read-allowed-origins
  "The configured allowlist as a set, or the empty set when nothing is set.

  Takes the value `clofin.config` read from the environment: `nil`, a raw
  comma-separated string, or a collection of entries. Every entry is validated;
  one bad entry refuses the whole list, because a list that silently dropped
  its malformed member would leave an operator believing an origin was allowed
  when it was not."
  [configured]
  (let [entries (cond
                  (nil? configured) []
                  (string? configured) (remove str/blank? (map str/trim (str/split configured #",")))
                  (coll? configured) (remove str/blank? (map (comp str/trim str) configured))
                  :else (refuse! configured "expected a comma-separated string"))]
    (into #{} (map read-origin) entries)))

;; ---------------------------------------------------------------------------
;; Answering
;; ---------------------------------------------------------------------------

(def ^:private allow-methods-header (str/join ", " allowed-methods))
(def ^:private allow-headers-header (str/join ", " allowed-request-headers))
(def ^:private expose-headers-header (str/join ", " exposed-response-headers))

(defn- matched-origin
  "The configured origin this request's `Origin` header names, or nil.

  Returns the value from `allowed`, never the value from the request. Every
  caller emits what this returns, so no code path exists along which a header
  supplied by a caller becomes a header sent to one."
  [allowed request]
  (when-let [origin (get-in request [:headers "origin"])]
    (get allowed (str/lower-case (str/trim origin)))))

(defn- preflight?
  "A CORS preflight, as the Fetch standard defines one: `OPTIONS`, carrying an
  `Origin` and an `Access-Control-Request-Method`. An `OPTIONS` without those
  is an ordinary request and is left to the router, which answers `405`."
  [request]
  (and (= :options (:request-method request))
       (get-in request [:headers "origin"])
       (get-in request [:headers "access-control-request-method"])))

(defn- with-vary-origin
  "Add `origin` to the response's `Vary`.

  Present on **every** response while the allowlist is active, including those
  that carry no CORS headers at all. A cache that stored one origin's answer
  and served it to another would hand out an `Access-Control-Allow-Origin` for
  somebody else, and `Vary` is the only thing that stops it. Appending rather
  than setting, because a handler may have set one."
  [response]
  (update-in response [:headers "vary"]
             (fn [existing]
               (cond
                 (str/blank? (str existing)) "origin"
                 (some #(= "origin" (str/lower-case (str/trim %)))
                       (str/split (str existing) #","))
                 existing
                 :else (str existing ", origin")))))

(defn- preflight-response
  "The answer to an allowed preflight.

  `204` with no body, and the four headers a browser reads. The method and
  header lists are **fixed** — they are this service's answer to \"what may be
  sent here\", not an echo of what was asked for. A browser compares its
  intended request against them and refuses on its own side if it does not fit,
  which is why an unsupported method needs no branch here."
  [origin]
  {:status 204
   :headers {"access-control-allow-origin" origin
             "access-control-allow-methods" allow-methods-header
             "access-control-allow-headers" allow-headers-header
             "access-control-max-age" (str max-age-seconds)
             "vary" "origin"}
   :body nil})

(defn wrap-cors
  "Answer cross-origin requests from configured origins, and nobody else.

  With no configured origin this returns `handler` unchanged — the same
  function — so an unconfigured service is byte-for-byte the service that
  existed before this middleware did.

  With an allowlist:

  - a **preflight** from a listed origin is answered `204` with the fixed
    method and header lists, without reaching the router;
  - a preflight from an unlisted origin is passed to the handler below, which
    answers exactly what it answers today — a `405` with an `Allow` header, or
    a `404` — carrying no CORS header, so the browser refuses the request it
    was asking about;
  - an **actual** request from a listed origin gets its response marked with
    that origin and the readable-header list. The request itself is executed
    either way: see this namespace's docstring on why that is not an access
    control."
  [handler configured]
  (let [allowed (read-allowed-origins configured)]
    (if (empty? allowed)
      handler
      (fn [request]
        (if-let [origin (matched-origin allowed request)]
          (if (preflight? request)
            (preflight-response origin)
            (-> (handler request)
                (assoc-in [:headers "access-control-allow-origin"] origin)
                (assoc-in [:headers "access-control-expose-headers"] expose-headers-header)
                with-vary-origin))
          (with-vary-origin (handler request)))))))
