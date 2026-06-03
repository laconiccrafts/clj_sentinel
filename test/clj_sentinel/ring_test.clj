(ns clj_sentinel.ring-test
  (:require
    [clj_sentinel.core :as sentinel]
    [clj_sentinel.ring :as sentinel.ring]
    [clojure.test :refer :all]))


(defn- capture-service
  "Builds a synchronous alert service for Ring middleware tests."
  [sent-messages]
  (sentinel/build-service
    {:enabled? true
     :app-name "demo"
     :env :prod
     :bot-token "token"
     :chat-id "123"
     :dispatch-fn (fn [task] (task))
     :now-fn (constantly 0)
     :send-fn (fn [_ text] (swap! sent-messages conj text))}))


(deftest response-status-alerts-on-500
  (let [sent-messages (atom [])
        app
        (sentinel.ring/wrap-exception-alerts
          (fn [_request]
            {:status 500
             :body "boom"})
          (capture-service sent-messages))
        response
        (app {:request-method :get
              :uri "/orders"})]
    (is (= 500 (:status response)))
    (is (= 1 (count @sent-messages)))
    (is (.contains (first @sent-messages)
                   "request=GET /orders"))))


(deftest annotated-response-uses-original-exception
  (let [sent-messages (atom [])
        exception (ex-info "boom" {})
        app
        (sentinel.ring/wrap-exception-alerts
          (fn [_request]
            (sentinel.ring/annotate-response
              {:status 500
               :body "boom"}
              {:exception exception
               :support-code "ERR-ABCD1234"}))
          (capture-service sent-messages))]
    (app {:request-method :get
          :uri "/orders"})
    (is (= 1 (count @sent-messages)))
    (is (.contains (first @sent-messages)
                   "message=boom"))
    (is (.contains (first @sent-messages)
                   "support-code=ERR-ABCD1234"))))


(deftest non-alertable-status-does-not-send
  (let [sent-messages (atom [])
        app
        (sentinel.ring/wrap-exception-alerts
          (fn [_request]
            {:status 400
             :body "bad request"})
          (capture-service sent-messages))]
    (app {:request-method :get
          :uri "/orders"})
    (is (empty? @sent-messages))))


(deftest thrown-exceptions-alert-and-rethrow
  (let [sent-messages (atom [])
        exception (ex-info "boom" {})
        app
        (sentinel.ring/wrap-exception-alerts
          (fn [_request]
            (throw exception))
          (capture-service sent-messages))]
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"boom"
          (app {:request-method :get
                :uri "/orders"})))
    (is (= 1 (count @sent-messages)))))


(deftest status-classifier-can-suppress-exception-alerts
  (let [sent-messages (atom [])
        app
        (sentinel.ring/wrap-exception-alerts
          (fn [_request]
            (throw (ex-info "bad request" {})))
          (capture-service sent-messages)
          {:exception-status-fn
           (fn [_request _exception]
             400)})]
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"bad request"
          (app {:request-method :get
                :uri "/orders"})))
    (is (empty? @sent-messages))))
