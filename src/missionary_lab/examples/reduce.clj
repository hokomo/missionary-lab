(ns missionary-lab.examples.reduce
  (:require
   [missionary.core :as m]))

;;; Problem: Reducing with a task-producing reducing function

;;; Version 1: Executing the task in the reducing function
;;;
;;; Works but isn't correct. We are blocking the carrier thread.
;;;
;;; The `m/?` operator inside the reducing function is *not* Missionary's
;;; parking operator as it doesn't appear within a Missionary evaluation
;;; context. It is instead a *blocking* operation that ties up the underlying
;;; Java thread (provided by Missionary for convenience at the top level).

(defn task-reduce-1 [rf init flow]
  (m/reduce #(m/? (rf %1 %2)) init flow))

(comment
  (m/? (task-reduce-1 #(m/sp (+ %1 %2)) 0 (m/seed (range 10))))
  )

;;; Version 2: Resolving the cycle with an `m/rdv`
;;;
;;; Doesn't work and is incorrect.
;;;
;;; Deadlocks on `(m/? (rdv x))` in `feedback-1`. The graceful termination of
;;; the upstream flow `(apply f (poll rdv) args)` has no effect on the
;;; termination of the `feedback-1` flow. The latter still has to execute the
;;; remainder of its final branch, which is exactly the problematic `(m/? (rdv
;;; x))`.

(defn poll [task]
  (m/ap (m/? (m/?> (m/seed (repeat task))))))

(defn feedback-1 [f & args]
  (m/ap
   (let [rdv (m/rdv)
         x (m/?> (apply f (poll rdv) args))]
     (m/? (rdv x))
     x)))

(defn task-reductions-2 [rf init flow]
  (feedback-1 #(m/ap (m/amb init (m/? (m/?> (m/zip rf %1 %2))))) flow))

(defn task-reduce-2 [rf init flow]
  (m/reduce {} nil (task-reductions-2 rf init flow)))

(comment
  (m/? (task-reduce-2 #(m/sp (+ %1 %2)) 0 (m/seed (range 10))))
  )

;;; Version 3: Resolving the cycle with a volatile
;;;
;;; Works and is correct.

(defn task-reductions-3 [rf init flow]
  (m/ap
    (let [acc (volatile! init)
          x (m/?> flow)]
      (vreset! acc (m/? (rf @acc x))))))

(defn task-reduce-3 [rf init flow]
  (m/reduce {} nil (task-reductions-3 rf init flow)))

(comment
  (m/? (task-reduce-3 #(m/sp (+ %1 %2)) 0 (m/seed (range 10))))
  )

;;; Version 4: Resolving the cycle with an `m/mbx`
;;;
;;; Works and is correct.
;;;
;;; This is like version 2 except with `m/mbx` instead of `m/rdv`.

(defn feedback-2 [f & args]
  (m/ap
   (let [mbx (m/mbx)
         x (m/?> (apply f (poll mbx) args))]
     (mbx x)
     x)))

(defn task-reductions-4 [rf init flow]
  (feedback-2 #(m/ap (m/amb init (m/? (m/?> (m/zip rf %1 %2))))) flow))

(defn task-reduce-4 [rf init flow]
  (m/reduce {} nil (task-reductions-4 rf init flow)))

(comment
  (m/? (task-reduce-4 #(m/sp (+ %1 %2)) 0 (m/seed (range 10))))
  )
