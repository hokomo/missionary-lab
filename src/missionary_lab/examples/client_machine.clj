(ns missionary-lab.examples.client-machine
  (:require
   [clojure.tools.logging :as log]
   [missionary.core :as m]
   [missionary-lab.examples.client.wrapper :as w]
   [missionary-lab.examples.client.core :as c]
   [missionary-lab.util :as u]))

;;; Problem: Model a state machine driven by client events
;;;
;;; Implement a state machine that we'll call "count-or-job" ("coj"). It must do
;;; the following while respecting the client API (in particular, it must
;;; correctly read the client state only from the client thread):
;;;
;;; 1. Wait for the client's tick counter to reach a number divisible by
;;;    `div` (a positive integer) and launch a potentially failing `job` (a
;;;    Missionary task) executing asynchronously in the background.
;;;
;;; 2. Wait for one of two things to happen, whichever is first: (a) the tick
;;;    counter reaches the value `div` + `offset` (a positive integer) or (b)
;;;    the job completes successfully.
;;;
;;; 2a. If the tick value `div` + `offset` is reached first, the job is
;;;     cancelled and the machine goes back to its initial state when the job
;;;     has fully terminated.
;;;
;;; 2b. If the job completed successfully first, the machine immediately goes
;;;     back to its initial state.

;;; Executor

(defn executor
  "Return a flow producing results of executions of tasks pulled from `mbx`. All
  tasks are executed concurrently."
  [mbx]
  (m/ap
    (let [task (m/?> ##Inf (u/continually mbx))]
      (try (m/? (u/logged task)) (catch Throwable _)))))

(defn executor-exec
  "Send off a `task` for execution to an executor backed by `mbx`. Return a
  canceller for the task."
  [mbx task]
  (let [dfv (m/dfv)]
    (mbx (m/any dfv task))
    #(dfv nil)))

;;; Machine

(defn machine-loop
  "Return a flow of [`event` `behavior`] pairs that describes the evolution of a
  state machine as it consumes events from the flow `events`. A behavior is a
  1-ary function that accepts an event and returns either a new behavior (to be
  used for accepting the next event) or a `reduced` (which terminates the
  machine). `init` is the initial behavior. The first value produced by the flow
  is [nil `init`]."
  [init events]
  (letfn [(rf [[_acc f] event]
            (let [g (f event)]
              (if (reduced? g) g [event g])))]
    (m/reductions rf [nil init] events)))

(defn machine
  "Return a task that is a state machine driven by the flow `events`. `ctor` is a
  1-ary function accepting the machine's \"plumbing\" and returning the initial
  behavior. The plumbing is a map of `:push` and `:exec`. `:push` is the
  provided `rdv` which can be used to inject an event into the machine's event
  flow. `:exec` is a 1-ary function accepting a task to execute on the machine's
  accompanying executor and returning its canceller. `events` is the primary
  flow of events fed to the machine loop."
  ([ctor events]
   (m/sp (m/? (machine (m/rdv) (m/mbx) ctor events))))
  ([rdv mbx ctor events]
   (let [events (u/select events (u/continually rdv))
         exec (partial executor-exec mbx)
         plumbing {:push rdv :exec exec}
         init (ctor plumbing)]
     (m/any (m/reduce {} nil (machine-loop init events))
            (m/reduce {} nil (executor mbx))))))

;;; Count-or-Job

(defn coj-job
  "Return a task emulating some work. It sleeps for a random number of seconds
  from the interval `work-for` and then either terminates with the number of
  seconds slept, or fails with probability `p`."
  [work-for p]
  (m/sp
    (let [delay (apply u/rand-in work-for)]
      (m/? (m/sleep (* delay 1000)))
      (if (< (rand) p)
        (throw (ex-info "Fail" {}))
        delay))))

(defn coj-machine
  "Return the initial behavior of a \"count-or-job\" state machine, parameterized
  by `div` (a positive integer), `offset` (a positive integer), and `job` (a
  Missionary task)."
  [div offset job {:keys [push exec] :as _plumbing}]
  (let [job (m/sp
              (let [res (try {:val (m/? job)} (catch Throwable t {:err t}))]
                (m/? (push (merge {:tag :job} res)))))]
    (letfn [(wait-div [state]
              (fn [{:keys [tag]}]
                (case tag
                  :tick
                  (let [{:keys [ticks]} (c/state)]
                    (log/info "Tick:" ticks)
                    (if (zero? (mod ticks div))
                      (do (log/info "Starting job!")
                          (wait-coj (assoc state :start ticks :proc (exec job))))
                      (wait-div state))))))
            (wait-coj [{:keys [start proc] :as state}]
              (fn [{:keys [tag val err]}]
                (case tag
                  :tick
                  (let [{:keys [ticks]} (c/state)]
                    (log/info "Waiting:" ticks)
                    (if (= ticks (+ start offset))
                      (do (log/info "Ticks reached first:" ticks)
                          (if proc
                            (do (proc) (wait-job {}))
                            (wait-div {})))
                      (wait-coj state)))
                  :job
                  (if val
                    (do (log/info "Job finished first:" val)
                        (wait-div {}))
                    (do (log/info err "Job failed")
                        (wait-coj (dissoc state :proc)))))))
            (wait-job [state]
              (fn [{:keys [tag val err]}]
                (case tag
                  :job
                  (do (if val
                        (log/info "Job cancelled:" val)
                        (log/info err "Job cancelled"))
                      (wait-div state))
                  (wait-job state))))]
      (wait-div {}))))

(comment
  (c/restart!)

  ;; Start the state machine
  (def m
    (u/monitor
     (machine (partial coj-machine 10 7 (coj-job [5 10] 0.5))
              (w/client-events :tick))))

  ;; Cancel the machine
  (m)
  )
