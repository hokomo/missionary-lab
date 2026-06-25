(ns missionary-lab.examples.client.wrapper
  (:require
   [clojure.tools.logging :as log]
   [missionary.core :as m]
   [missionary-lab.examples.client.core :as c]
   [missionary-lab.util :as u])
  (:import
   java.util.concurrent.Executor))

;;; Core Client API (similar to Java Swing's in spirit)

(comment
  ;; Start the client
  (c/restart!)

  ;; Can run code on the client thread
  (c/invoke! #(log/info "Hello from the client thread"))

  ;; Must not access the state off the client thread
  (try (c/state) (catch Throwable e (log/error e)))

  ;; Must access the state on the client thread
  (c/invoke! #(log/info "State:" (c/state)))

  ;; Can register event listeners that run on the client thread
  (c/register! :tick :key (fn [_] (log/info "Tick:" (c/state))))
  (c/unregister! :key)
  )

;;; Missionarified Client API

(def client-executor
  "An Executor that runs code on the client thread."
  (reify Executor
    (execute [_ r]
      (c/invoke! #(.run r)))))

(defmacro to-client []
  "Ensure that the remainder of a Missionary context will start off by executing
  on the client thread. *MUST* be used within an existing Missionary context."
  `(u/to client-executor))

(defn client-events
  "Return a flow of client events of type `type`."
  [type]
  (m/observe
   (fn [cb]
     (c/register! type cb cb)
     (log/info "Registered flow for" type)
     #(do (c/unregister! cb)
          (log/info "Unregistered flow for" type)))))

(comment
  (c/restart!)

  ;; Can run code on the client thread
  (m/? (m/via client-executor (log/info "Hello from the client thread!")))
  (m/? (m/sp (to-client) (log/info "Hello from the client thread!")))

  ;; Can register event flows that transfer from the client thread
  (m/? (m/reduce (fn [_ _] (log/info "Tick:" (c/state)))
                 nil (client-events :tick)))
  )
