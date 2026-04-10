(ns missionary-lab.reduce
  (:require
   [missionary.core :as m]))

;;; Problem: Reducing with a task-producing reducing function

(defn task-reduce-1 [rf init flow]
  ;; The `m/?` operator inside of the reducing function is *not* Missionary's
  ;; parking operator as it doesn't appear within a Missionary evaluation
  ;; context. It is instead a *blocking* operation that ties up the underlying
  ;; Java thread (provided by Missionary for convenience at the top level).
  (m/reduce #(m/? (rf %1 %2)) init flow))

(comment
  ;; Version 1. Works but isn't correct. We are blocking the carrier thread.
  (m/? (task-reduce-1 #(m/sp (+ %1 %2)) 0 (m/seed (range 10))))
  )

(defn poll [task]
  (m/ap (m/? (m/?> (m/seed (repeat task))))))

(defn feedback [f & args]
  (m/ap
   (let [rdv (m/rdv)
         x (m/?> (apply f (poll rdv) args))]
     (m/? (rdv x))
     x)))

(defn task-reductions-2 [rf init flow]
  (feedback #(m/ap (m/amb init (m/? (m/?> (m/zip rf %1 %2))))) flow))

(defn task-reduce-2 [rf init flow]
  (m/reduce {} nil (task-reductions-2 rf init flow)))

(comment
  ;; Version 2. Doesn't work. Deadlocks on `m/rdv`'s "give" task.
  (m/? (task-reduce-2 #(m/sp (+ %1 %2)) 0 (m/seed (range 10))))
  )

(defn task-reductions-3 [rf init flow]
  (m/ap
    (let [acc (volatile! init)
          x (m/?> flow)]
      (vreset! acc (m/? (rf @acc x))))))

(defn task-reduce-3 [rf init flow]
  (m/reduce {} nil (task-reductions-3 rf init flow)))

(comment
  ;; Version 3. Works and is correct.
  (m/? (task-reduce-3 #(m/sp (+ %1 %2)) 0 (m/seed (range 10))))
  )
