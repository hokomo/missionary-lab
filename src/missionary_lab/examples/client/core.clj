(ns missionary-lab.examples.client.core
  (:require
   [clojure.tools.logging :as log])
  (:import
   clojure.lang.PersistentQueue))

;;; Core Client API (similar to Java Swing's in spirit)

(defn make []
  {;; Client state that is not important for users.
   :internal (atom nil)
   ;; Client state that is important for users. Accessed only from the client
   ;; thread.
   :external (volatile! nil)
   ;; Client state that stores the underlying thread.
   :instance (volatile! nil)})

(defonce
  ^{:private true :doc "The global client instance."}
  the-client
  (make))

(defn- dispatch
  "Invoke `f` with `args`, ignoring and logging all exceptions."
  [f & args]
  (try (apply f args) (catch Throwable e (log/error "Dispatch error" e))))

(defn- process
  "The client loop."
  [{:keys [internal external] :as _client}]
  (loop []
    ;; Update state
    (vswap! external update :ticks inc)
    ;; Dispatch tick event
    (let [{:keys [listeners]} @internal]
      (doseq [f (vals (:tick listeners))]
        (dispatch f {:tag :tick})))
    ;; Dispatch invokes
    (let [[{:keys [invokes]} _] (swap-vals! internal update :invokes pop)]
      (when (not-empty invokes)
        (dispatch (first invokes))))
    ;; Loop
    (when-not (:done? @internal)
      (Thread/sleep 1000)
      (recur))))

(defn- main
  "The entrypoint of the client thread. `ready?` is a promise delivered once all
  client state has been initialized."
  [{:keys [internal external] :as client} ready?]
  (try
    (log/info "Client started")
    (reset! internal {:done? false :listeners {} :invokes (PersistentQueue/EMPTY)})
    (vreset! external {:ticks 0})
    (deliver ready? true)
    (process client)
    (catch Throwable e
      (log/error "Client error" e))
    (finally
      (reset! internal nil)
      (vreset! external nil)
      (log/error "Client exited"))))

(defn done!
  "Request the client to shut down. Return whether the client was running."
  ([]
   (done! the-client))
  ([{:keys [internal] :as _client}]
   (some? (swap! internal #(some-> % (assoc :done? true))))))

(defn register!
  "Register a 1-ary function as a listener for a client event."
  ([type key f]
   (register! the-client type key f))
  ([{:keys [internal] :as _client} type key f]
   (assert @internal "Client not started")
   (swap! internal update-in [:listeners type] assoc key f)
   key))

(defn unregister!
  "Unregister a previously registered listener."
  ([key]
   (unregister! the-client key))
  ([{:keys [internal] :as _client} key]
   (assert @internal "Client not started")
   (swap! internal update :listeners update-vals #(dissoc % key))
   key))

(defn restart!
  "(Re)Start the global client instance."
  ([]
   (restart! the-client))
  ([{:keys [instance] :as client}]
   (locking instance
     ;; Shut down an existing instance, if any
     (when-let [{:keys [future]} @instance]
       (when (done! client)
         @future))
     ;; Launch a new instance
     (let [ready? (promise)
           future (future
                    (vswap! instance assoc :thread (Thread/currentThread))
                    (main client ready?))]
       @ready?
       (vswap! instance assoc :future future)))))

(defn state
  "Fetch the current external client state. Must be run on the client thread."
  ([]
   (state the-client))
  ([{:keys [external instance] :as _client}]
   (when-not (= (Thread/currentThread) (:thread @instance))
     (throw (ex-info "Not on the client thread" {})))
   @external))

(defn invoke!
  "Invoke a 0-ary function on the client thread. If called from the client thread,
  `f` is invoked immediately, otherwise at some point in the future."
  ([f]
   (invoke! the-client f))
  ([{:keys [internal instance] :as _client} f]
   (assert @internal "Client not started")
   (if (= (Thread/currentThread) (:thread @instance))
     (f)
     (swap! internal update :invokes conj f))
   f))

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
