(ns otplike.csi.core
  (:require
   [clojure.core.match :as match :refer [match]]
   [otplike.process :as p]
   [otplike.trace :as t]
   [taoensso.timbre :as log]
   [cognitect.transit :as transit]
   [org.httpkit.server :as http-kit]))

(defn- transit-writer [stream]
  (transit/writer stream :json
    {:handlers
     {otplike.process.Pid
      (transit/write-handler "pid"
        (fn [pid]
          (into {} pid)))}}))

(defn- transit-reader [stream]
  (transit/reader stream :json
    {:handlers
     {"pid"
      (transit/read-handler
        (fn [{:keys [id pname]}]
          (otplike.process/->Pid id pname)))}}))

(defn- transit-send [channel form]
  (let [os (java.io.ByteArrayOutputStream. 4096)]
    (-> os transit-writer (transit/write form))
    (http-kit/send! channel (.toString os))))

(defn- transit-read [string]
  (transit/read
    (transit-reader
      (java.io.ByteArrayInputStream. (.getBytes string java.nio.charset.StandardCharsets/UTF_8)))))

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
      [::ok (apply fn args)]
      [::error :badfn func])
    [::error :badfn func]))

(p/proc-defn- wsproc [channel]
  (let [watchdog
        (p/spawn-link
          (p/proc-fn []
            (p/flag :trap-exit true)
            (p/receive!
              message
              (when (http-kit/open? channel)
                (log/debugf "wsproc-watchdog %s message received, closing WebSocket, message=%s" (p/self) message)
                (http-kit/close channel)))))]
    
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

            [::ok _]
            (recur)))

        message
        (do
          (transit-send channel [::message message])
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
        (http-kit/send! channel
          {:status 426 :headers {"upgrade" "websocket"}})))))

#_(p/trace t/crashed? println)

#_(do @#'p/*processes)

#_(doseq [[pid _] @@#'p/*processes]
    (p/! pid ::terminate!))

#_[otplike.process :as p]


;;; demo code
#_(defn start []
    (http-kit/run-server #'http-kit-handler {:port 8086}))

#_(start)



