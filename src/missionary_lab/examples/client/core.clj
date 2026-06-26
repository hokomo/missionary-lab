(ns missionary-lab.examples.client.core
  (:require
   [clojure.tools.logging :as log])
  (:import
   clojure.lang.PersistentQueue))

;;; Core Client API (similar to Java Swing's in spirit)

(defn make []
  {;; State used for client start and shutdown. A volatile with a map of:
   ;;
   ;; - :thread -- The thread the client loop is executing on.
   ;; - :future -- The future that will receive the result of the client loop.
   ;; - :wait -- A promise delivered just before the client enters its loop.
   :control (volatile! nil)
   ;; State used for external requests. An atom with a map of:
   ;;
   ;; - :listeners -- A map of event types to seqs of 1-ary functions.
   ;; - :invokes -- A queue of 0-ary functions.
   ;; - :done? -- Whether shutdown was requested.
   :requests (atom nil)
   ;; State used for the game. Accessed only from the client thread. A volatile
   ;; with a map of:
   ;;
   ;; - :ticks -- The number of game ticks that have elapsed. 1 tick per second.
   :game (volatile! nil)})

(defonce
  ^{:doc "The global client instance."}
  the-client
  (make))

(defn- dispatch
  "Invoke `f` with `args`, ignoring and logging all exceptions."
  [f & args]
  (try (apply f args) (catch Throwable e (log/error "Dispatch error" e))))

(defn- process
  "The client loop."
  [{:keys [game requests] :as client}]
  (loop []
    (let [{:keys [listeners invokes done?] :as requests}
          (first (swap-vals! requests assoc :invokes []))]
      ;; Update game state
      (vswap! game update :ticks inc)
      ;; Dispatch tick event
      (doseq [f (vals (:tick listeners))]
        (dispatch f {:tag :tick}))
      ;; Dispatch invokes
      (doseq [f invokes]
        (dispatch f))
      ;; Loop
      (if done?
        {:requests requests :game @game}
        (do (Thread/sleep 1000)
            (recur))))))

(defn- main
  "The entrypoint of the client thread."
  [{:keys [requests game] :as client} wait]
  (try
    (log/info "Client started")
    (reset! requests {:listeners {} :invokes [] :done? false})
    (vreset! game {:ticks 0})
    (deliver wait true)
    (process client)
    (catch Throwable e
      (log/error "Client error" e))
    (finally
      (vreset! game nil)
      (reset! requests nil)
      (log/error "Client exited"))))

(defn state
  "Fetch the current game state. Must be run on the client thread."
  ([]
   (state the-client))
  ([{:keys [control game] :as _client}]
   (assert (= (Thread/currentThread) (:thread @control))
           "Not on the client thread")
   @game))

(defn register!
  "Register the 1-ary function `f` as a listener for client events of type `type`,
  under the key `key`. The key can be used to unregister the listener."
  ([type key f]
   (register! the-client type key f))
  ([{:keys [requests] :as _client} type key f]
   (swap! requests #(do (assert % "Client not started")
                        (update-in % [:listeners type] assoc key f)))
   key))

(defn unregister!
  "Unregister a listener previously registered under the key `key`."
  ([key]
   (unregister! the-client key))
  ([{:keys [requests] :as _client} key]
   (swap! requests #(do (assert % "Client not started")
                        (update % :listeners update-vals
                                (fn [m] (dissoc m key)))))
   key))

(defn invoke!
  "Invoke the 0-ary function `f` on the client thread. If called from the client
  thread, `f` is invoked immediately, otherwise at some point in the future."
  ([f]
   (invoke! the-client f))
  ([{:keys [control requests] :as _client} f]
   (if (= (Thread/currentThread) (:thread @control))
     (f)
     (swap! requests #(do (assert % "Client not started")
                          (update % :invokes conj f))))
   f))

(defn start!
  "Start the client. Clear all previously registered listeners and queued invokes.
  Return when the client is ready to accept new registrations and invokes."
  ([]
   (start! the-client))
  ([{:keys [control] :as client}]
   (locking control
     (when-not @control
       (let [wait (promise)
             future (future
                      (vswap! control assoc
                              :thread (Thread/currentThread)
                              :wait wait)
                      (main client wait))]
         (vswap! control assoc :future future)
         @wait)))))

(defn stop!
  "Stop the client. Return when the client thread has terminated."
  ([]
   (stop! the-client))
  ([{:keys [control requests] :as client}]
   (locking control
     (when-let [{:keys [future]} @control]
       (swap! requests assoc :done? true)
       (let [res @future]
         (vreset! control nil)
         res)))))

(defn restart!
  "Stop then start the client."
  ([]
   (restart! the-client))
  ([{:keys [control] :as client}]
   (locking control
     (stop! client)
     (start! client))))

(comment
  ;; Start the client
  (restart!)

  ;; Can run code on the client thread
  (invoke! #(log/info "Hello from the client thread"))

  ;; Must not access the state off the client thread
  (try (state) (catch Throwable e (log/error e)))

  ;; Must access the state on the client thread
  (invoke! #(log/info "State:" (state)))

  ;; Can register event listeners that run on the client thread
  (register! :tick :key (fn [_] (log/info "Tick:" (state))))
  (unregister! :key)
  )
