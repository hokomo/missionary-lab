(ns missionary-lab.reflection
  (:require
   [missionary.core :as m])
  (:import
   java.io.Closeable))

(set! *warn-on-reflection* true)

;;; Problem: Reflection warnings within conditionals

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
