(ns clofin.http.response
  "Response construction, including RFC 9457 problem details.

  Response bodies are Clojure data; JSON encoding happens once, in middleware.
  Handlers therefore return values that a test can assert on directly."
  (:require [clofin.error :as err]))

(defn response
  ([status] (response status nil))
  ([status body] {:status status :headers {} :body body}))

(defn ok       [body] (response 200 body))
(defn created  [location body] (assoc-in (response 201 body) [:headers "location"] location))
(defn accepted [body] (response 202 body))
(defn no-content [] (response 204 nil))

(defn with-header [resp k v] (assoc-in resp [:headers k] v))

(defn problem
  "An RFC 9457 `application/problem+json` body.

  Payment APIs are consumed by systems that must branch on failure, so the
  machine-readable `type` matters more than the prose. `type` is a stable URI
  path under /problems/ that can be documented and never changes meaning."
  [{:keys [status type title detail instance errors]}]
  {:status status
   :headers {"content-type" "application/problem+json"}
   :body (cond-> {"type"   (str "https://clofin.dev/problems/" (name type))
                  "title"  title
                  "status" status}
           detail       (assoc "detail" detail)
           instance     (assoc "instance" instance)
           (seq errors) (assoc "errors" errors))})

(defn error->problem
  "Render a domain error as a problem response.

  Only the error's own message and explicitly-declared public data reach the
  caller. Anything else in `ex-data` stays in the logs."
  [t {:keys [correlation-id]}]
  (let [data   (ex-data t)
        type   (:clofin/error data)
        {:keys [status title problem-type]} (get err/error-types type)]
    (problem {:status status
              ;; A category may report under a problem type other than its own
              ;; name, so that two categories a client treats alike share one
              ;; stable `type` URI while keeping different status codes.
              :type (or problem-type type)
              :title title
              :detail (ex-message t)
              :instance correlation-id
              :errors (dissoc data :clofin/error :clofin/message)})))
