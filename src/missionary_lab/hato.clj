(ns missionary-lab.hato
  (:require
   [clojure.tools.logging :as log]
   [hato.client :as hato]
   [missionary.core :as m])
  (:import
   java.util.concurrent.CompletableFuture))

(defn request [& {:keys [id] :as req}]
  (fn [s! f!]
    (let [cf (hato/request
              (assoc req :async? true)
              #(do (log/info "s!" id) (s! %))
              #(do (log/info "f!" id) (f! %)))]
      #(.cancel ^CompletableFuture cf true))))

(defn pull [par reqs]
  (->> (m/ap
         (let [{:keys [id] :as req} (m/?> par (m/seed reqs))]
           (log/info "Sending" id req)
           (try (m/? (request req))
                (log/info "Done" id)
                (catch Exception e
                  (log/info "Crashed" id (type e))
                  (throw e)))))
       (m/reduce conj [])))

(defn random-reqs [ok err]
  (shuffle (sequence (comp cat (map-indexed #(assoc %2 :id %1)))
                     [(repeat ok {:url "https://httpbin.org/status/200"})
                      (repeat err {:url "https://httpbin.org/status/500"})])))

(comment
  (m/? (pull 8 (random-reqs 4 4)))
  )
