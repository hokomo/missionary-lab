(ns missionary-lab.client
  (:require
   [clojure.tools.logging :as log])
  (:import
   clojure.lang.PersistentQueue
   java.util.concurrent.Executor))

(defonce
  ^{:private true :doc "Client state that is not important for users."}
  internal
  (atom nil))

(defonce
  ^{:private true
    :doc "Client state that is important for users. Accessed only from the client thread."}
  external
  (volatile! nil))

(defn- dispatch
  "Invoke `f` with `args`, ignoring and logging all exceptions."
  [f & args]
  (try (apply f args) (catch Throwable e (log/error "Dispatch error" e))))

(defn- process
  "The client loop."
  []
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
  [ready?]
  (try
    (log/info "Client started")
    (reset! internal {:done? false :listeners {} :invokes (PersistentQueue/EMPTY)})
    (vreset! external {:ticks 0})
    (deliver ready? true)
    (process)
    (catch Throwable e
      (log/error "Client error" e))
    (finally
      (reset! internal nil)
      (vreset! external nil)
      (log/error "Client exited"))))

(defn done!
  "Request the client to shut down. Return whether the client was running."
  []
  (some? (swap! internal #(some-> % (assoc :done? true)))))

(defn register!
  "Register a 1-ary function as a listener for a client event."
  [type key f]
  (assert @internal "Client not started")
  (swap! internal update-in [:listeners type] assoc key f)
  key)

(defn unregister!
  "Unregister a previously registered listener."
  [key]
  (assert @internal "Client not started")
  (swap! internal update :listeners update-vals #(dissoc % key))
  key)

(defn later!
  "Invoke a 0-ary function on the client thread at some point in the future."
  [f]
  (assert @internal "Client not started")
  (swap! internal update :invokes conj f)
  f)

(defonce
  ^{:private true :doc "The global client instance."}
  instance
  (volatile! nil))

(defn restart!
  "(Re)Start the global client instance."
  []
  (locking instance
    ;; Shut down an existing instance, if any
    (when-let [{:keys [future]} @instance]
      (when (done!)
        @future))
    ;; Launch a new instance
    (let [ready? (promise)
          future (future
                   (vswap! instance assoc :thread (Thread/currentThread))
                   (main ready?))]
      @ready?
      (vswap! instance assoc :future future))))

(defn state
  "Fetch the current external client state. Must be run on the client thread."
  []
  (when-not (= (Thread/currentThread) (:thread @instance))
    (throw (ex-info "Not on the client thread" {})))
  @external)

(def executor
  "A reentrant Executor that runs code on the client thread."
  (reify Executor
    (execute [_ r]
      ;; NOTE: Make the executor reentrant.
      (if (= (Thread/currentThread) (:thread @instance))
        (.run r)
        (later! #(.run r))))))
