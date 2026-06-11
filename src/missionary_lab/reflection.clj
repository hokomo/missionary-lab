(ns missionary-lab.reflection
  (:require
   [missionary.core :as m])
  (:import
   java.io.Closeable))

(set! *warn-on-reflection* true)

;;; Problem 1: Reflection warnings within conditionals

(defn foo ^Closeable []
  nil)

;;; Version 1: No reflection warnings with or without Missionary

(defn reflection-1a []
  (.close (foo)))

(defn reflection-1b []
  (m/sp (.close (foo))))

;;; Version 2: Reflection warnings with Missionary

(defn reflection-2a []
  (if true (.close (foo))))

(defn reflection-2b []
  (m/sp (if true (.close (foo)))))

;;; Problem 2: Performance warning

(defn reflection-3 [& flows]
  (m/ap
   (loop [[f & fs] flows]
     (if f
       (m/amb= (m/?> f) (recur fs))
       (m/amb)))))
