(ns missionary-lab.examples.client.wrapper
  (:require
   [clojure.tools.logging :as log]
   [missionary.core :as m]
   [missionary-lab.examples.client.core :as c]
   [missionary-lab.util :as u])
  (:import
   java.util.concurrent.Executor))

;;; Missionarified Client API

(defn client-executor
  "An Executor that runs code on the client thread."
  ([]
   (client-executor c/the-client))
  ([client]
   (reify Executor
     (execute [_ r]
       (c/invoke! client #(.run r))))))

(defmacro to-client [& args]
  "Ensure that the remainder of a Missionary context will start off by executing
  on the client thread. *MUST* be used within an existing Missionary context."
  `(u/to (client-executor ~@args)))

(defn client-events
  "Return a flow of client events of type `type`."
  ([type]
   (client-events c/the-client type))
  ([client type]
   (m/observe
    (fn [cb]
      (c/register! client type cb cb)
      (log/info "Registered flow for" type)
      #(do (c/unregister! client cb)
           (log/info "Unregistered flow for" type))))))

(comment
  (c/restart!)

  ;; Can run code on the client thread
  (m/? (m/via (client-executor) (log/info "Hello from the client thread!")))
  (m/? (m/sp (to-client) (log/info "Hello from the client thread!")))

  ;; Can register event flows that transfer from the client thread
  (m/? (m/reduce (fn [_ _] (log/info "Tick:" (c/state)))
                 nil (client-events :tick)))
  )
