(ns otplike.csi.core
  (:require [clojure.core.match :as match :refer [match]]
            [otplike.process :as p :refer [!]]
            [otplike.timer :as timer]
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


(defn- format-call [func args]
  (pr-str (concat [func] args)))


(defn- apply-sym [func args]
  (if-let [fn (some-> func resolve deref)]
    (if (fn? fn)
      [:ok (apply fn args)]
      [:error [:badfn func args]])
    [:error [:badfn func args]]))


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


(defn- handle-message [state channel request]
  (match request
    [::cast func args]
    (do
      (log/debugf "wsproc %s :: cast %s" (p/self) (format-call func args))
      (match (apply-sym func args)
        [::error reason]
        (p/exit reason)

        _
        :ok)
      state)

    [::call func args correlation-id]
    (do
      (log/debugf
       "wsproc %s :: call [%s] %s"
       (p/self) correlation-id (format-call func args))
      (match (apply-sym func args)
        [:error reason]
        (p/exit reason)

        [:ok ret]
        (let [ret (if (p/async? ret) ret (p/async-value ret))]
          (log/tracef
           "wsproc %s :: call [%s] ok, sending response"
           (p/self) correlation-id)
          (p/with-async [result ret]
            (transit-send
             channel [::return (convert-nil result) correlation-id])
            (log/tracef
             "wsproc %s :: call [%s] response sent" (p/self) correlation-id)
            state))))

    message
    (do
      (transit-send channel [::message (convert-nil message)])
      state)))


(p/proc-defn- wsproc [channel start-promise]
  (let [watchdog (p/spawn-link watchdog-proc [channel])]
    (log/debugf
     "wsproc %s :: watchdog process spawned, pid=%s" (p/self) watchdog)
    (transit-send channel [::self (p/self)])
    (deliver start-promise :started)
    (p/receive!
      ::go :ok
      (after 60000
        (p/exit :start-timeout)))
    (timer/send-interval 3000 ::ping)
    (loop [state {:ping-data 1}]
      (p/receive!
        [::terminate reason]
        (do
          (log/debugf "wsproc %s :: 'terminate' message received" (p/self))
          (p/exit reason))

        [::pong data]
        ;; TODO check the data, define how much pongs can be missing
        (recur state)

        ::ping
        (let [data (-> state :ping-data inc)]
          (transit-send channel [::ping data])
          (recur (assoc state :ping-data data)))

        message
        (let [new-state (p/await?! (handle-message state channel message))]
          (recur new-state))))))


(defn- context-> [context & interceptor-fns]
  (loop [context context
         interceptors interceptor-fns]
    (if-let [interceptor (first interceptors)]
      (match (interceptor context)
        [:next new-context]
        (recur new-context (rest interceptors))

        result
        result)
      [:next context])))


(defn- handle-connect [channel request {:keys [on-connect] :as _opts}]
  (let [start-promise (promise)
        ws-pid (p/spawn wsproc [channel start-promise])]
    (case (deref start-promise 10000 :timeout)
      :started :ok) ;; TODO handle timeout
    (log/debugf "handler :: connection process spawned, pid=%s" ws-pid)
    (let [interceptor-fns (->> on-connect (mapv :after) (filter some?) (doall))
          context {:request request :pid ws-pid}
          result (apply context-> context interceptor-fns)]
      (match result
        [:next new-context]
        (do
          (! ws-pid ::go)
          [:ok ws-pid])

        [:stop reason _new-context]
        (do
          (! ws-pid [::terminate reason])
          [:error reason])))))


(defn- handle-close-fn [_channel _opts ws-pid]
  (fn handle-close [_event]
    (log/debugf "handler :: connection closed, pid=%s" ws-pid)
    (! ws-pid [::terminate :connection-closed])))


(defn- handle-receive-fn [channel {:keys [on-receive] :as opts} ws-pid]
  (let [interceptor-fns (->> on-receive (mapv :before) (filter some?) (doall))]
    (fn handle-receive [data]
      (let [message
            (try
              (transit-read data)
              (catch Exception ex
                (log/error ex "handler :: cannot parse message data.")
                (! ws-pid [::terminate (p/ex->reason ex)])))]
        (log/tracef "handler :: message: %s" message)
        (match message
          [::pong _]
          (! ws-pid message)

          [request context]
          (let [context {:pid ws-pid :request request :context context}
                result (apply context-> context interceptor-fns)]
            (match result
              [:next {:request request1}]
              (! ws-pid request1)

              [:reply result {:request [::call _func _args correlation-id]}]
              (transit-send
               channel [::return (convert-nil result) correlation-id])

              [:noreply context]
              :ok

              [:stop reason context]
              (! ws-pid [::terminate reason]))))))))


(defn http-kit-handler
  ([]
   (http-kit-handler {}))
  ([opts]
   (fn [request]
     (log/debug "handler :: request received")
     (http-kit/with-channel request channel
       (if (http-kit/websocket? channel)
         (match (handle-connect channel request opts)
           [:ok ws-pid]
           (do
             (http-kit/on-close channel
               (handle-close-fn channel opts ws-pid))
             (http-kit/on-receive channel
               (handle-receive-fn channel opts ws-pid)))

           [:error reason]
           (log/error "handler :: connection error" reason))
         (do
           (log/warn "handler :: not a WebSocket connection")
           (http-kit/send!
            channel {:status 426 :headers {"upgrade" "websocket"}})))))))


;; -------------
;; Demo


(defn- start []
  (http-kit/run-server (http-kit-handler) {:port 8086}))


#_(start)
