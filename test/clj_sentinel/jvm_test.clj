(ns clj_sentinel.jvm-test
  (:require
    [clj_sentinel.core :as sentinel]
    [clj_sentinel.jvm :as sentinel.jvm]
    [clojure.test :refer :all]))


(deftest uncaught-handler-forwards-alerts
  (let [sent-messages (atom [])
        previous-handler (Thread/getDefaultUncaughtExceptionHandler)
        service
        (sentinel/build-service
          {:enabled? true
           :app-name "demo"
           :env :prod
           :bot-token "token"
           :chat-id "123"
           :dispatch-fn (fn [task] (task))
           :send-fn (fn [_ text] (swap! sent-messages conj text))
           :now-fn (constantly 0)})
        exception (ex-info "boom" {})]
    (try
      (sentinel.jvm/install-uncaught-exception-handler! service)
      (let [handler (Thread/getDefaultUncaughtExceptionHandler)]
        (.uncaughtException
          ^Thread$UncaughtExceptionHandler handler
          (Thread/currentThread)
          exception))
      (is (= 1 (count @sent-messages)))
      (is (.contains (first @sent-messages)
                     "<code>uncaught-thread</code>"))
      (is (.contains (first @sent-messages)
                     "<b>Exception</b>"))
      (finally
        (sentinel.jvm/uninstall-uncaught-exception-handler!)
        (Thread/setDefaultUncaughtExceptionHandler previous-handler)))))
