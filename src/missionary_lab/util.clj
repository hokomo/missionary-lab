(ns missionary-lab.util
  (:require
   [clojure.tools.logging :as log]
   [missionary.core :as m]))

;;; Math

(defn rand-in
  "Return a random integer in the interval [`from`, `to`]."
  [from to]
  (+ from (rand-int (inc (- to from)))))

;;; Tasks

(defmacro to
  "Ensure that the remainder of a Missionary context will start off by executing
  on a thread from the given executor. *MUST* be used within an existing
  Missionary context."
  [executor]
  `(m/? (m/via ~executor nil)))

(defn spontaneously?
  "Return a task that invokes `s`/`f` with 2 arguments when `task` terminates
  successfully/with a failure: a boolean flag indicating whether the termination
  was spontaneous, and the termination result. `s`/`f` must return the value to
  provide to the success/failure continuation, and must not throw or block."
  [task s f]
  (fn [s! f!]
    (let [cancelled? (atom false)
          cancel (task #(s! (s (not @cancelled?) %))
                       #(f! (f (not @cancelled?) %)))]
      #(do (reset! cancelled? true)
           (cancel)))))

(defn logged
  "Return a task that executes `task` and forwards its result, but also logs it:
  whether it terminated successfully, due to an application failure, or due to a
  cancellation request."
  [task]
  (spontaneously? task
                  (fn [_spontaneous? v]
                    (log/info "Process terminated successfully:" v)
                    v)
                  (fn [spontaneous? e]
                    (if spontaneous?
                      (log/error e "Process failed")
                      (log/error e "Process cancelled"))
                    e)))

;;; Flows

(defn select
  "Return a flow that non-deterministically transfers from any of its input
  flows."
  [& flows]
  (m/ap
    (loop [[f & fs] flows]
      (if f
        (m/amb= (m/?> f) (recur fs))
        (m/amb)))))

(defn continually
  "Return an infinite flow that transfers results of `task` executions."
  [task]
  (m/ap (loop [] (m/amb (m/? task) (recur)))))
