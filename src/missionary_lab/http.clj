(ns missionary-lab.hato
  (:require
   [babashka.http-client :as bb]
   [clj-http.client :as ch]
   [clojure.tools.logging :as log]
   [hato.client :as hato]
   [java-http-clj.core :as jhc]
   [missionary.core :as m])
  (:import
   java.util.concurrent.CompletableFuture
   java.util.concurrent.Executors
   java.util.concurrent.Future))

(defn request-hato [& {:keys [id] :as req}]
  (fn [s! f!]
    (let [cf (hato/request
              (assoc req :async? true)
              #(do (log/info "s!" id) (s! %))
              #(do (log/info "f!" id) (f! %)))]
      #(.cancel ^CompletableFuture cf true))))

(defn request-hato-sync [& {:keys [id] :as req}]
  (m/via (Executors/newVirtualThreadPerTaskExecutor)
    (hato/request req)))

(defn request-bb [& {:keys [id] :as req}]
  (fn [s! f!]
    (let [cf (bb/request
              (assoc req
                     :async true
                     :async-then #(do (log/info "s!" id) (s! %))
                     :async-catch #(do (log/info "f!" id) (f! (:ex %)))))]
      #(.cancel ^CompletableFuture cf true))))

(defn request-bb-sync [& {:keys [id] :as req}]
  (m/via (Executors/newVirtualThreadPerTaskExecutor)
    (bb/request req)))

(defn request-jhc [& {:keys [id] :as req}]
  (m/sp
    (let [{:keys [status] :as res}
          (m/? (fn [s! f!]
                 (let [cf (jhc/send-async
                           req {}
                           #(do (log/info "s!" id) (s! %))
                           #(do (log/info "f!" id) (f! %)))]
                   #(.cancel ^CompletableFuture cf true))))]
      (if (= status 500)
        (throw (ex-info "Unsuccessful response" {}))
        res))))

(defn request-jhc-sync [& {:keys [id] :as req}]
  (m/sp
    (let [{:keys [status] :as res}
          (m/? (m/via (Executors/newVirtualThreadPerTaskExecutor)
                 (jhc/send req)))]
      (if (= status 500)
        (throw (ex-info "Unsuccessful response" {:res res}))
        res))))

(defn request-ch [& {:keys [id] :as req}]
  (fn [s! f!]
    (let [fut (ch/request
               (assoc req
                      :async? true
                      :oncancel #(do (log/info "f!" id)
                                     (f! (ex-info "Request interrupted"
                                                  {:req req}))))
               #(do (log/info "s!" id) (s! %))
               #(do (log/info "f!" id) (f! %)))]
      #(.cancel ^Future fut true))))

(defn request-ch-sync [& {:keys [id] :as req}]
  (m/via (Executors/newVirtualThreadPerTaskExecutor)
    (ch/request req)))

(defn pull [request par reqs]
  (->> (m/ap
         (let [{:keys [id] :as req} (m/?> par (m/seed reqs))]
           (log/info "Sending" id req)
           (try (m/? (request req))
                (log/info "Done" id)
                (catch Exception e
                  (log/info "Crashed" id (type e))
                  (throw e)))))
       (m/reduce conj [])))

(def ok-req
  {:url "https://httpbin.org/status/200"
   :uri "https://httpbin.org/status/200"
   :method :get})

(def err-req
  {:url "https://httpbin.org/status/500"
   :uri "https://httpbin.org/status/500"
   :method :get})

(defn random-reqs [ok err]
  (shuffle (sequence (comp cat (map-indexed #(assoc %2 :id %1)))
                     [(repeat ok ok-req) (repeat err err-req)])))

(comment
  (m/? (pull request-hato 8 (random-reqs 4 4)))
  (m/? (pull request-hato-sync 8 (random-reqs 4 4)))

  (m/? (pull request-bb 8 (random-reqs 4 4)))
  (m/? (pull request-bb-sync 8 (random-reqs 4 4)))

  (m/? (pull request-jhc 8 (random-reqs 4 4)))
  (m/? (pull request-jhc-sync 8 (random-reqs 4 4)))

  (m/? (pull request-ch 8 (random-reqs 4 4)))
  (m/? (pull request-ch-sync 8 (random-reqs 4 4)))

  (((request-hato ok-req) #(log/info :ok %) #(log/info :ko %)))
  (((request-hato err-req) #(log/info :ok %) #(log/info :ko %)))
  (((request-hato-sync ok-req) #(log/info :ok %) #(log/info :ko %)))
  (((request-hato-sync err-req) #(log/info :ok %) #(log/info :ko %)))

  (((request-bb ok-req) #(log/info :ok %) #(log/info :ko %)))
  (((request-bb err-req) #(log/info :ok %) #(log/info :ko %)))
  (((request-bb-sync ok-req) #(log/info :ok %) #(log/info :ko %)))
  (((request-bb-sync err-req) #(log/info :ok %) #(log/info :ko %)))

  (((request-jhc ok-req) #(log/info :ok %) #(log/info :ko %)))
  (((request-jhc err-req) #(log/info :ok %) #(log/info :ko %)))
  (((request-jhc-sync ok-req) #(log/info :ok %) #(log/info :ko %)))
  (((request-jhc-sync err-req) #(log/info :ok %) #(log/info :ko %)))

  (((request-ch ok-req) #(log/info :ok %) #(log/info :ko %)))
  (((request-ch err-req) #(log/info :ok %) #(log/info :ko %)))
  (((request-ch-sync ok-req) #(log/info :ok %) #(log/info :ko %)))
  (((request-ch-sync err-req) #(log/info :ok %) #(log/info :ko %)))
  )
