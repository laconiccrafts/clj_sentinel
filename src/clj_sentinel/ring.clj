(ns clj_sentinel.ring
  "Generic Ring middleware for sentinel HTTP alerting."
  (:require
    [clj_sentinel.core :as sentinel]))


(def ^:private response-event-key
  "Response key used to carry alert context across exception handlers."
  ::event)


(defn annotate-response
  "Attaches sentinel event data to a Ring response."
  [response event]
  (assoc response response-event-key event))


(defn- default-notify?
  "Returns true when the HTTP status should emit an alert."
  [{:keys [status]}]
  (and (int? status)
       (<= 500 status 599)))


(defn- synthetic-response-exception
  "Builds a fallback exception for alertable responses without one."
  [request status]
  (ex-info
    "HTTP handler returned alertable status."
    {:request-method (:request-method request)
     :status status
     :uri (:uri request)}))


(defn- default-event
  "Builds the default sentinel event from Ring request/response data."
  [{:keys [exception request response status]}]
  (let [annotated-event (or (get response response-event-key) {})
        resolved-exception
        (or (:exception annotated-event)
            exception
            (synthetic-response-exception request status))]
    (merge
      {:source :http-request
       :request-method (:request-method request)
       :uri (:uri request)
       :exception resolved-exception}
      (dissoc annotated-event :exception))))


(defn wrap-exception-alerts
  "Wraps a Ring handler and emits sentinel alerts for alertable failures."
  ([handler service]
   (wrap-exception-alerts handler service {}))
  ([handler service
    {:keys [event-fn exception-status-fn notify?]
     :or {event-fn default-event
          exception-status-fn (fn [_request _exception] 500)
          notify? default-notify?}}]
   (fn
     ([request]
      (try
        (let [response (handler request)
              status (:status response)]
          (when (notify? {:request request
                          :response response
                          :status status})
            (sentinel/notify!
              service
              (event-fn {:request request
                         :response response
                         :status status})))
          response)
        (catch Exception exception
          (let [status (exception-status-fn request exception)]
            (when (notify? {:request request
                            :exception exception
                            :status status})
              (sentinel/notify!
                service
                (event-fn {:request request
                           :exception exception
                           :status status}))))
          (throw exception))))
     ([request respond raise]
      (handler request
               (fn [response]
                 (let [status (:status response)]
                   (when (notify? {:request request
                                   :response response
                                   :status status})
                     (sentinel/notify!
                       service
                       (event-fn {:request request
                                  :response response
                                  :status status}))))
                 (respond response))
               (fn [exception]
                 (let [status (exception-status-fn request exception)]
                   (when (notify? {:request request
                                   :exception exception
                                   :status status})
                     (sentinel/notify!
                       service
                       (event-fn {:request request
                                  :exception exception
                                  :status status}))))
                 (raise exception)))))))


(defn exception-alerts-middleware
  "Returns a Ring middleware function that wraps handlers with alerts."
  ([service]
   (exception-alerts-middleware service {}))
  ([service options]
   (fn [handler]
     (wrap-exception-alerts handler service options))))
