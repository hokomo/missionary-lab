(ns missionary-lab.util
  (:require
   [clojure.tools.logging :as log]
   [clojure.pprint :as pp]
   [manifold.deferred :as md]
   [missionary.core :as m])
  (:import
   clojure.lang.IFn
   clojure.lang.IBlockingDeref
   clojure.lang.IDeref
   clojure.lang.IPending))

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
  "Return a task that executes `task` and forwards its result, but also logs
  when and how it terminates."
  [task]
  (spontaneously? task
                  (fn [spontaneous? v]
                    (if spontaneous?
                      (log/info "Process terminated successfully:" v)
                      (log/info "Process cancelled successfully:" v))
                    v)
                  (fn [spontaneous? e]
                    (if spontaneous?
                      (log/error e "Process failed")
                      (log/error e "Process cancelled"))
                    e)))

(defrecord Awaitable [proc d]
  IFn
  (invoke [this]
    (proc)
    this)
  IPending
  (isRealized [this]
    (realized? d))
  IDeref
  (deref [this]
    @d)
  IBlockingDeref
  (deref [this ms val]
    (deref d ms val)))

(defmethod print-method Awaitable [{:keys [d] :as _a} w]
  (print-method d w))

(defmethod pp/simple-dispatch Awaitable [{:keys [d] :as _a}]
  (print-method d *out*))

(defn awaitable
  "Return a task that executes `task` and forwards its result, but whose canceller
  additionally acts as a future. The future implements the `IPending`, `IDeref`
  and `IBlockingDeref` interfaces, and provides knowledge on whether the task
  terminated and with what result."
  [task]
  (fn [s! f!]
    (let [d (md/deferred)
          proc (task #(do (md/success! d %) (s! %))
                     #(do (md/error! d %) (f! %)))]
      (->Awaitable proc d))))

(defn monitor
  "Wrap `task` with `logged` and `awaitable` and run it."
  [task]
  ((awaitable (logged task)) {} {}))

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
