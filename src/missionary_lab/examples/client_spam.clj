(ns missionary-lab.examples.client-spam
  (:require
   [clojure.tools.logging :as log]
   [missionary.core :as m]
   [missionary-lab.examples.client.wrapper :as w]
   [missionary-lab.examples.client.core :as c]
   [missionary-lab.util :as u]))

;;; Problem: Avoiding flow registration spam

;;; Version 1
;;;
;;; The flow will be registered/unregistered on every call to `tick-wait-1`.

(defn tick-wait-1
  "Return a task that waits for the client's tick counter to advance by 1."
  []
  (m/sp
   (w/to-client)
   (let [tick (:ticks (c/state))]
     (m/? (m/reduce
           (fn [_acc _event]
             (let [tick' (:ticks (c/state))]
               (when (>= tick' (inc tick))
                 (reduced [tick tick']))))
           nil (w/client-events :tick))))))

(comment
  (c/restart!)

  (m/? (m/sp
        (w/to-client)
        (dotimes [_ 3]
          (log/info "Before:" (:ticks (c/state)))
          (m/? (tick-wait-1))
          (log/info "After:" (:ticks (c/state))))))
  )

;;; Version 2
;;;
;;; We create an `m/stream` of the flow of events but because there's only a
;;; single subscriber the flow will still be registered/unregistered on every
;;; call to `tick-wait-2`.

(defn tick-wait-2
  "Like `tick-wait-1` but accepts the flow of tick events as a parameter."
  [ticks]
  (m/sp
   (w/to-client)
   (let [tick (:ticks (c/state))]
     (m/? (m/reduce
           (fn [_acc _event]
             (let [tick' (:ticks (c/state))]
               (when (>= tick' (inc tick))
                 (reduced [tick tick']))))
           nil ticks)))))

(comment
  (m/? (let [events (m/stream (w/client-events :tick))]
         (m/sp
           (w/to-client)
           (dotimes [_ 3]
             (log/info "Before:" (:ticks (c/state)))
             (m/? (tick-wait-2 events))
             (log/info "After:" (:ticks (c/state)))))))
  )

;;; Version 3
;;;
;;; We use a no-op `m/reduce` task to keep the created `m/stream` alive and
;;; ticking. This prevents the registration/unregistration of the flow on every
;;; call to `tick-wait-2`.

(comment
  (m/? (let [events (m/stream (w/client-events :tick))]
         (m/any
          (m/reduce {} nil events)
          (m/sp
           (w/to-client)
           (dotimes [_ 3]
             (log/info "Before:" (:ticks (c/state)))
             (m/? (tick-wait-2 events))
             (log/info "After:" (:ticks (c/state))))))))
  )
