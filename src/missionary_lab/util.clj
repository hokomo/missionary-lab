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
  was spontaneous, and the termination result. `s` and `f` must not throw or
  block."
  [task s f]
  (fn [s! f!]
    (let [cancelled? (atom false)
          cancel (task #(do (s (not @cancelled?) %) (s! %))
                       #(do (f (not @cancelled?) %) (f! %)))]
      #(do (reset! cancelled? true)
           (cancel)))))

(defn log-abrupt
  "Return a task that will log abrupt termination (spotaneous failures)."
  [task]
  (spontaneously? task {} #(when %1 (log/error %2 "Terminated abruptly"))))

;;; Flows

(defmacro doflow
  "A convenient shorthand for a particular style of `reduce`. `e` is bound to the
  transfered value and `body` is the body of the reducing function."
  [[e flow] & body]
  `(m/reduce (fn [~'_ ~e] ~@body) nil ~flow))

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
