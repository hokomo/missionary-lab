(ns missionary-lab.spam
  (:require
   [clojure.tools.logging :as log]
   [missionary.core :as m]
   [missionary-lab.base :as b]
   [missionary-lab.client :as c]
   [missionary-lab.util :as u]))

;;; Problem: Avoiding flow registration spam

(defn tick-wait-1
  "Return a task that waits for the client's tick counter to advance by 1."
  []
  (m/sp
    (let [tick (b/on-client (:ticks (c/state)))]
      (m/? (u/doflow [_ (b/client-events :tick)]
             (let [tick' (:ticks (c/state))]
               (when (>= tick' (inc tick))
                 (reduced [tick tick']))))))))

(comment
  (c/restart!)

  ;; Version 1. The flow has to be registered/unregistered for every call to
  ;; `tick-wait-1`.
  (m/? (m/sp
         (b/on-client
           (dotimes [_ 3]
             (log/info "Before: " (:ticks (c/state)))
             (m/? (tick-wait-1))
             (log/info "After: " (:ticks (c/state)))))))
  )

(defn tick-wait-2
  "Like `tick-wait-1` but accepts the flow of tick events as a parameter."
  [ticks]
  (m/sp
    (let [tick (b/on-client (:ticks (c/state)))]
      (m/? (u/doflow [_ ticks]
             (let [tick' (:ticks (c/state))]
               (when (>= tick' (inc tick))
                 (reduced [tick tick']))))))))

(comment
  ;; Version 2. We create a `stream` from the flow of events but because there's
  ;; only a single subscriber the flow still has to be registered/unregistered
  ;; for each every to `tick-wait-2`.
  (m/? (let [events (m/stream (b/client-events :tick))]
         (m/sp
           (b/on-client
             (dotimes [_ 3]
               (log/info "Before: " (:ticks (c/state)))
               (m/? (tick-wait-2 events))
               (log/info "After: " (:ticks (c/state))))))))

  ;; Version 3. We use a no-op `reduce` task to keep the created `stream` alive
  ;; and ticking.
  (m/? (let [events (m/stream (b/client-events :tick))]
         (u/fastest
          (m/reduce {} nil events)
          (m/sp
            (b/on-client
              (dotimes [_ 3]
                (log/info "Before: " (:ticks (c/state)))
                (m/? (tick-wait-2 events))
                (log/info "After: " (:ticks (c/state)))))))))
  )
