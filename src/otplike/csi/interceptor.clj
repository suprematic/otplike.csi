(ns otplike.csi.interceptor
  (:require [clojure.core.match :as match :refer [match]]
            [otplike.process :as p :refer [!]]
            [taoensso.timbre :as log])
  (:import [otplike.process Pid TRef]))


;; All inteceptor callbacks are called NOT in the context of the delegate
;; process.
;;
;; Two kinds of interceptors:
;;; receive interceptors - provide two callbacks: `:before` and `:after`
;;; send interceptors - provide one (for now) callback - `:before`
;;
;; Returns:
;;; [:next context]
;;; [:reply result context]
;;; [:noreply context]
;;; [:stop reason context]
;;
;; CSI api
;;; (csi/execution-context context) - get the context attached to the
;;; request/message.
;;; (csi/send context message) - send a message to the delegate process.
;;
;; TODO
;;; - think about error handling
#_(def receive-interceptor
    {:before
     (fn [context]
       #_(case request-kind
           ::call
           (let [[f-sym args] data]
             (comment "check fn and args")
             [:next f-sym args])

           ::cast

           ::message))

     :after
     (fn [context]
       #_(case request-kind
           ::call
           (let [[f-sym args] data]
             (comment "check fn and args")
             [:next f-sym args])

           ::cast

           ::message))})


;; ====================================================================
;; API
;; ====================================================================


(defn send! [{:keys [pid] :as context} msg]
  (! pid msg))
