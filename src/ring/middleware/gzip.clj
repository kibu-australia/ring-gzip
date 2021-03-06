(ns ring.middleware.gzip
  "Ring gzip compression."
  (:require [clojure.java.io :as io])
  (:import (java.io InputStream
                    Closeable
                    File
                    PipedInputStream
                    PipedOutputStream)
           (java.util.zip GZIPOutputStream)))

(defn- accepts-gzip?
  [req]
  (if-let [accepts (get-in req [:headers "accept-encoding"])]
    ;; Be aggressive in supporting clients with mangled headers (due to
    ;; proxies, av software, buggy browsers, etc...)
    (re-seq
      #"(gzip\s*,?\s*(gzip|deflate)?|X{4,13}|~{4,13}|\-{4,13})"
      accepts)))

;; Set Vary to make sure proxies don't deliver the wrong content.
(defn- set-response-headers
  [headers]
  (if-let [vary (get headers "vary")]
    (-> headers
      (assoc "Vary" (str vary ", Accept-Encoding"))
      (assoc "Content-Encoding" "gzip")
      (dissoc "Content-Length")
      (dissoc "vary"))
    (-> headers
      (assoc "Vary" "Accept-Encoding")
      (assoc "Content-Encoding" "gzip")
      (dissoc "Content-Length"))))

(def ^:private supported-status? #{200, 201, 202, 203, 204, 205 403, 404})

(defn- unencoded-type?
  [headers]
  (if (headers "content-encoding")
    false
    true))

(def ^:private min-length 859)

(defn- supported-size?
  [resp]
  (let [{body :body} resp]
    (cond
      (string? body) (> (count body) min-length)
      (seq? body) (> (count body) min-length)
      (instance? File body) (> (.length body) min-length)
      :else true)))

(defn- supported-response?
  [resp]
  (let [{:keys [status headers]} resp]
    (and (supported-status? status)
         (unencoded-type? headers)
         (supported-size? resp))))

(defn- compress-body
  [body]
  (let [p-in (PipedInputStream.)
        p-out (PipedOutputStream. p-in)]
    (future
      (with-open [out (GZIPOutputStream. p-out)]
        (if (seq? body)
          (doseq [string body] (io/copy (str string) out))
          (io/copy body out)))
      (when (instance? Closeable body)
        (.close body)))
    p-in))

(defn- gzip-response
  [resp]
  (-> resp
    (update-in [:headers] set-response-headers)
    (update-in [:body] compress-body)))

(defn wrap-gzip
  "Middleware that compresses responses with gzip for supported user-agents."
  [handler]
  (fn [req]
    (if (accepts-gzip? req)
      (let [resp (handler req)]
        (gzip-response resp))
      (handler req))))
