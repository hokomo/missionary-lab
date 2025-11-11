(ns missionary-lab.machine
  (:require
   [clojure.tools.logging :as log]
   [missionary.core :as m]
   [missionary-lab.base :as b]
   [missionary-lab.client :as c]
   [missionary-lab.util :as u]))

(defn log-abrupt
  "Return a task that will log abrupt termination (spotaneous failures)."
  [task]
  (u/spontaneously? task {} #(when %1 (log/error "Abrupt termination" %2))))

;;; Executor

(defn executor
  "Return a flow producing results of executions of tasks pulled from `mbx`. All
  tasks are executed concurrently."
  [mbx]
  (m/ap
    (let [task (m/?> ##Inf (u/continually mbx))]
      (try (m/? (log-abrupt task)) (catch Throwable _)))))

(defn exec
  "Send off a `task` for execution to an executor backed by `mbx`. Return a
  canceller for the task."
  [mbx task]
  (let [dfv (m/dfv)]
    (mbx (u/fastest dfv task))
    #(dfv nil)))

;;; Machine

(defn machine-loop
  "Return a flow of [`event` `behavior`] pairs that describes the evolution of a
  state machine as it receives `events`. A behavior is a 1-ary function that
  accepts an event and returns a new behavior, while `init` is the initial
  behavior. The first value produced by the flow is [nil `init`]."
  [init events]
  (m/reductions (fn [[_ f] event] [event (f event)]) [nil init] events))

(defn machine-top
  "Return a task that represents the machine top level: the concurrent execution
  of the machine's loop and an accompanying executor. `push` is an `rdv` that
  can be used to inject an event into the machine loop's event flow. `ctor` is a
  3-ary function accepting `exec`, `push` and `cancel`, and returning the
  machine's initial behavior. `exec` is a 1-ary function accepting a task to to
  execute on the machine's executor and returning a canceller. `cancel` is a
  canceller for the whole top level. `events` is the primary flow of events fed
  to the machine loop."
  [push ctor events]
  (let [mbx (m/mbx)
        dfv (m/dfv)
        init (ctor (partial exec mbx) push #(dfv nil))
        events (u/select events (u/continually push))]
    (u/fastest dfv (m/join (constantly nil)
                           (m/reduce {} nil (machine-loop init events))
                           (m/reduce {} nil (executor mbx))))))

(defn machine-go
  "Instantiate a `machine-top` task with the given `ctor` and `events`. Return a
  function of 2 arities. The 0-ary is a canceller. The 1-ary is a `push` for the
  machine."
  [ctor events]
  (let [push (m/rdv)
        proc ((log-abrupt (machine-top push ctor events)) {} {})]
    (fn
      ([] (proc))
      ([e] (push e)))))

(defn machine-pusher
  "Return a task that will apply `s`/`f` to `task`'s success/failure result and
  deliver the final result as an event via `push`."
  [push task s f]
  (m/sp (try (let [result (m/? task)] (when s (m/? (push (s result)))))
             (catch Throwable t (when f (m/? (push (f t))))))))

;;; Problem: Modeling a state machine

(defn rand-in
  "Return a random integer in the interval [`from`, `to`]."
  [from to]
  (+ from (rand-int (inc (- to from)))))

(defn example-job
  "Return a task representing some example work by sleeping for a random number of
  seconds in the interval `work-for`. The task can fail with a chance of
  `chance` in which case it sleeps for `fail-for` and throws."
  [work-for chance fail-for]
  (m/sp
    (if (< (rand) chance)
      (do (m/? (m/sleep (* (apply rand-in fail-for) 1000)))
          (throw (ex-info "Crash" {})))
      (let [delay (apply rand-in work-for)]
        (m/? (m/sleep (* delay 1000) delay))))))

(defn example-pusher
  "Return a `machine-pusher` whose result functions wrap the result into a map
  with a `:tag` key equal to `tag` and a `:val`/`:err` equal to the
  success/failure result."
  [push task tag & [s f]]
  (machine-pusher
   push task
   (fn [v] {:tag tag (or s :val) v})
   (fn [e] {:tag tag (or f :err) e})))

(defn example-machine
  "Return the initial behavior of an example state machine that is powered by
  various events, primarily those from the client. When started, the machine
  does the following in order:

  1. Waits for the client's tick counter to reach a number divisible by 10 (call
  this value `start`) and launches `example-job` in the background.

  2. Waits for one of two things to happen, whichever is first: (a) the tick
  counter reaches the value `start` + 7 or (b) the job completes successfully.

  2a. If the tick value `start` + 7 is reached first, the job is cancelled and
  the machine goes back to its initial state once the job has fully terminated.

  2b. If the job finished first, the machine immediately goes back to its
  initial state."
  [exec push cancel]
  (letfn [(job []
            (example-pusher push (example-job [5 10] 0.5 [3 3]) :job))
          (wait-start [state]
            (fn [{:keys [tag]}]
              (case tag
                :tick
                (let [{:keys [ticks]} (c/state)]
                  (log/info "Tick:" ticks)
                  (if (zero? (mod ticks 10))
                    (do (log/info "Starting!")
                        (wait-one (assoc state :start ticks :job (exec (job)))))
                    (wait-start state))))))
          (wait-one [{:keys [start job] :as state}]
            (fn [{:keys [tag val]}]
              (case tag
                :job
                (if val
                  (do (log/info "Job finished first:" val)
                      (wait-start {}))
                  (do (log/info "Job failed")
                      (wait-one (dissoc state :job))))
                :tick
                (let [{:keys [ticks]} (c/state)]
                  (log/info "Tick:" ticks)
                  (if (= ticks (+ start 7))
                    (do (log/info "Ticks reached first:" ticks)
                        (if job
                          (do (job) (wait-job {}))
                          (wait-start {})))
                    (wait-one state))))))
          (wait-job [state]
            (fn [{:keys [tag val err]}]
              (case tag
                :job
                (do (log/info "Job cancelled:" (or val err))
                    (wait-start state))
                (wait-job state))))]
    (wait-start {})))

(comment
  (c/restart!)

  ;; Start the state machine
  (def m (machine-go example-machine (b/client-events :tick)))

  ;; Client events are expected to be delivered from the client thread
  (try (m/? (m {:tag :tick})) (catch Throwable e (log/error e)))

  ;; Invalid events crash the machine
  (try (m/? (m :invalid)) (catch Throwable e (log/error e)))

  ;; Cancel the machine
  (m)
  )
