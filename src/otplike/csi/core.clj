(ns otplike.csi.core
  (:require
   [clojure.core.match :as match :refer [match]]
   [otplike.process :as p]
   [otplike.trace :as t]
   [taoensso.timbre :as log]
   [cognitect.transit :as transit]
   [org.httpkit.server :as http-kit])
  (:import [otplike.process Pid TRef]))

(def ^:private transit-write-handlers
  {Pid
   (transit/write-handler
    "pid" (fn [^Pid pid] {:id (.id pid)}))
   TRef
   (transit/write-handler
    "otp-ref" (fn [^TRef tref] {:id (.id tref)}))})

(def ^:private transit-read-handlers
  {"pid"
   (transit/read-handler
    (fn [{:keys [id]}]
      (Pid. id)))
   "otp-ref"
   (transit/read-handler
    (fn [{:keys [id]}]
      (TRef. id)))})

(defn- transit-writer [stream]
  (transit/writer stream :json {:handlers transit-write-handlers}))

(defn- transit-reader [stream]
  (transit/reader stream :json {:handlers transit-read-handlers}))

(defn- transit-send [channel form]
  (let [os (java.io.ByteArrayOutputStream. 4096)]
    (-> os transit-writer (transit/write form))
    (http-kit/send! channel (.toString os))))

(defn- transit-read [string]
  (transit/read
   (transit-reader
    (java.io.ByteArrayInputStream.
     (.getBytes string java.nio.charset.StandardCharsets/UTF_8)))))

(defn- qstr [maybe-string]
  (if (string? maybe-string)
    (str \" maybe-string \")
    maybe-string))

(defn- format-call [func args]
  (letfn [(qstr [s]
            (if (string? s)
              (str \" s \") s))]
    (format "(%s %s)" func (->> args (map qstr) (interpose " ") (apply str)))))

(defn- apply-sym [func args]
  (if-let [fn (some->> func resolve deref)]
    (if (fn? fn)
      (apply fn args)
      [::error [:badfn func args]])
    [::error [:badfn func args]]))

(defn- convert-nil [value]
  (if (nil? value)
    ::nil
    value))

(p/proc-defn- watchdog-proc [channel]
  (p/flag :trap-exit true)
  (p/receive!
    [:EXIT _ reason]
    (when (http-kit/open? channel)
      (log/debugf
       "wsproc-watchdog %s message received, closing WebSocket, reason=%s"
       (p/self) reason)
      (transit-send channel [::exit reason])
      (http-kit/close channel))))

(p/proc-defn- wsproc [channel]
  (let [watchdog (p/spawn-link watchdog-proc [channel])]
    (log/debugf "wsproc %s :: watchdog process spawned, pid=%s" (p/self) watchdog)
    (transit-send channel [::self (p/self)])
    (loop []
      (p/receive!
        ::terminate
        (do
          (log/debugf "wsproc %s :: 'terminate' message received" (p/self))
          nil)

        [::cast func args]
        (do
          (log/debugf "wsproc %s :: cast %s" (p/self) (format-call func args))
          (match (apply-sym func args)
            [::error r]
            (p/exit r)

            _
            (recur)))

        [::call func args corr]
        (do
          (log/debugf "wsproc %s :: call [%s] %s" (p/self) corr (format-call func args))
          (match (-> (apply-sym func args) p/await?!)
            [::error r]
            (p/exit r)

            result
            (transit-send channel [::return (convert-nil result) corr]))
          (recur))

        message
        (do
          (transit-send channel [::message (convert-nil message)])
          (recur))))))


(defn http-kit-handler [request]
  (log/debug "handler :: request received")
  (http-kit/with-channel request channel
    (if (http-kit/websocket? channel)
      (let [wsproc-pid (p/spawn wsproc [channel])]
        (log/debugf "handler :: connection process spawned, pid=%s" wsproc-pid)

        (http-kit/on-close channel
          (fn [event]
            (log/debugf "handler :: connection closed, pid=%s" wsproc-pid)
            (p/! wsproc-pid ::terminate)))

        (http-kit/on-receive channel
          (fn [data]
            (let [message
                  (try
                    (transit-read data)
                    (catch Exception ex
                      (log/error ex "handler :: cannot parse message data.")
                      (p/! wsproc-pid ::terminate)))]

              (log/debugf "handler :: message: %s" message)
              (p/! wsproc-pid message)))))
      (do
        (log/warn "handler :: not a WebSocket connection")
        (http-kit/send!
         channel {:status 426 :headers {"upgrade" "websocket"}})))))

;; -------------
;; Demo

(defn- start []
  (http-kit/run-server #'http-kit-handler {:port 8086}))

#_(start)
