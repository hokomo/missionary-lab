(ns missionary-lab.util
  (:require
   [missionary.core :as m]))

;;; Tasks

(defmacro on
  "Similar to `via` but can only be used from within a Missionary context. Ensures
  that `body` will be run on `executor` but within a context that supports
  Missionary synchronizers."
  [executor & body]
  `(do (m/? (m/via ~executor)) ~@body))

(defn fastest
  "Like `race` but admits failed tasks."
  [& tasks]
  (m/absolve (apply m/race (map m/attempt tasks))))

(defn spontaneously?
  "When `task` terminates successfully/with a failure, invoke `s`/`f` with 2
  arguments: a boolean flag indicating whether the termination was spontaneous,
  and the termination result."
  [task s f]
  (fn [s! f!]
    (let [cancelled? (atom false)
          cancel (task #(do (s (not @cancelled?) %) (s! %))
                       #(do (f (not @cancelled?) %) (f! %)))]
      #(do (reset! cancelled? true)
           (cancel)))))

;;; Flows

(defmacro doflow
  "A convenient shorthand for `reduce`."
  [[e flow] & body]
  `(m/reduce (fn [~'_ ~e] ~@body) nil ~flow))

(defn select
  "A flow that non-deterministically transfers from any of its input flows."
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
