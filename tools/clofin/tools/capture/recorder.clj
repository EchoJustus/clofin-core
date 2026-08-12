(ns clofin.tools.capture.recorder
  "The tape. Every request the harness makes and every response it gets back.

  A scenario is written as ordinary code that calls `request!`; this namespace
  is what turns that into the record `clofin-trace` replays. Nothing is
  summarised on the way through: the raw response body is kept as the bytes
  arrived, the parsed form is kept beside it for convenience, and the step's
  narrative is kept separate from both so that no prose can be mistaken for
  captured output.

  **Why raw and parsed both.** The walkthrough shows values, so it needs the
  parsed form; `disclaimer-verbatim` compares bytes, so it needs the raw one.
  Deriving either from the other at render time would be the trace repository
  computing something, which is the one thing
  [ADR-0020](../../../../docs/ADR/0020-two-repositories-and-the-generate-replay-rules.md)
  says it never does.

  **Expectations are asserted, not decorated.** A step may declare the status
  it expects. When the live stack disagrees, the capture stops: a bundle whose
  narrative says *\"and the database refuses\"* beside a captured `201` would
  be a fake with a commit SHA on it, which is worse than no walkthrough at
  all. The expectation is recorded in the step as well, so a reader can see
  what the scenario claimed before the stack answered."
  (:require [clofin.tools.capture.provenance :as prov]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$Builder
            HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(defn recorder
  "A fresh tape for one scenario."
  [{:keys [base-url]}]
  {:base-url base-url
   :client   (-> (HttpClient/newBuilder)
                 (.connectTimeout (Duration/ofSeconds 10))
                 (.build))
   :steps    (atom [])})

(defn steps
  "The tape so far, in order."
  [rec]
  @(:steps rec))

(defn- record!
  [rec step]
  (let [n (count (swap! (:steps rec) conj step))]
    (swap! (:steps rec) assoc-in [(dec n) :n] n)
    (nth (steps rec) (dec n))))

(defn- parse-json
  "The response body as data, or nil when it is not JSON.

  Keys stay strings, exactly as `clofin.http.middleware` keeps request keys
  strings: this is a transcript of someone else's document, not a value this
  repository owns."
  [content-type body]
  (when (and (some-> content-type str/lower-case (str/includes? "json"))
             (not (str/blank? body)))
    (try (json/read-str body) (catch Exception _ nil))))

(defn- header-map
  [^java.net.http.HttpHeaders headers]
  (into (sorted-map)
        (for [[k v] (.map headers)]
          [k (str/join ", " v)])))

(defn request!
  "Perform one HTTP call against the captured stack and record it.

  `step` carries the presentation-layer material — an id, a title, the
  explanatory narrative — and the call itself. Returns the recorded step, so a
  scenario reads as a sequence of calls whose responses it can pick fields out
  of.

  Nothing is redacted, because there is nothing to redact: every actor id,
  counterparty and amount in these scenarios is synthetic, and a harness that
  quietly dropped headers would make the tape a summary."
  [rec {:keys [id title narrative method path query headers body expect-status
               kind account]
        :or   {method "GET" headers {} kind "http"}}]
  (let [base      (:base-url rec)
        url       (str base path (when (seq query)
                                   (str "?" (str/join "&" (for [[k v] query]
                                                            (str k "=" (java.net.URLEncoder/encode
                                                                        (str v) "UTF-8")))))))
        body-raw  (when body (json/write-str body :escape-slash false))
        sent      (cond-> (into (sorted-map) headers)
                    body-raw (assoc "content-type" "application/json"))
        builder   (reduce (fn [b [k v]] (.header ^HttpRequest$Builder b k (str v)))
                          (-> (HttpRequest/newBuilder (URI/create url))
                              (.timeout (Duration/ofSeconds 30)))
                          sent)
        req       (-> (if body-raw
                        (.method ^HttpRequest$Builder builder method
                                 (HttpRequest$BodyPublishers/ofString body-raw))
                        (.method ^HttpRequest$Builder builder method
                                 (HttpRequest$BodyPublishers/noBody)))
                      (.build))
        response  (.send ^HttpClient (:client rec) req (HttpResponse$BodyHandlers/ofString))
        res-raw   (.body response)
        res-heads (header-map (.headers response))
        status    (.statusCode response)
        step      {:id        id
                   :kind      kind
                   :title     title
                   :narrative narrative
                   :account   account
                   :request   {:method   method
                               :path     path
                               :query    (when (seq query) (into (sorted-map) query))
                               :headers  sent
                               :body     body
                               :body-raw body-raw}
                   :response  {:status    status
                               :headers   res-heads
                               :body      (parse-json (get res-heads "content-type") res-raw)
                               :body-raw  res-raw
                               :body-sha256 (prov/sha256 res-raw)}
                   :expected  (when expect-status {:status expect-status})}]
    (when (and expect-status (not= expect-status status))
      (throw (ex-info (format (str "capture refuses: step %s expected HTTP %s from %s %s "
                                   "and the captured stack answered %s. The scenario and the "
                                   "system disagree; a bundle recorded now would narrate one "
                                   "and show the other.\n%s")
                              id expect-status method path status res-raw)
                      {:step id :expected expect-status :actual status :body res-raw})))
    (record! rec step)))

(defn note!
  "Record something that is not a call — a heading, or an explanation of what
  the next few steps are for.

  A note carries no captured value and is marked as such, so that a renderer
  can never present prose as though it came off the wire."
  [rec {:keys [id title narrative]}]
  (record! rec {:id id :kind "note" :title title :narrative narrative}))

(defn sql!
  "Record a statement run directly against the database, and its outcome.

  Seeding actors and roles is done in SQL because CloFin deliberately has no
  endpoint that creates an actor or grants a role — an actor able to grant
  itself the approver role would make segregation of duties unenforceable
  however carefully the rule is written (UAT-005 §2). Those statements are
  part of what happened, so they are on the tape like anything else, including
  the ones that are *supposed* to fail: a refusal by a check constraint is one
  of the more convincing things this system does."
  [rec {:keys [id title narrative statement result]}]
  (record! rec {:id id
                :kind "sql"
                :title title
                :narrative narrative
                :statement statement
                :result result}))
