(ns clj_sentinel.core-test
  (:require
    [clj_sentinel.core :as sentinel]
    [clojure.test :refer :all]))


(defn- capture-service
  "Builds a synchronous alert service for unit tests."
  ([sent-messages]
   (capture-service sent-messages (constantly 0)))
  ([sent-messages now-fn]
   (sentinel/build-service
     {:enabled? true
      :app-name "demo"
      :env :prod
      :bot-token "token"
      :chat-id "123"
      :dispatch-fn (fn [task] (task))
      :now-fn now-fn
      :send-fn (fn [_ text] (swap! sent-messages conj text))})))


(deftest format-message-stays-sanitized
  (let [exception (ex-info "boom <bad> & worse"
                    {:auth-token "secret-token"})
        message
        (sentinel/format-message
          {:app-name "demo"
           :env :prod
           :stack-frame-limit 2}
          {:source :http-request
           :request-method :get
           :uri "/admin/orders?view=<all>"
           :support-code "ERR-ABCD1234"
           :exception exception
           :fingerprint "fp-1"
           :timestamp "2026-06-03T10:00:00Z"
           :extra-lines ["summary=<internal>&"]
           :session "cookie=secret"})]
    (is (.contains message "<b>demo</b>"))
    (is (.contains message "<code>http-request</code>"))
    (is (.contains message "<b>Exception</b>"))
    (is (.contains message "boom &lt;bad&gt; &amp; worse"))
    (is (.contains message "GET /admin/orders?view=&lt;all&gt;"))
    (is (.contains message "support-code"))
    (is (.contains message "ERR-ABCD1234"))
    (is (.contains message "summary=&lt;internal&gt;&amp;"))
    (is (.contains message "<b>Stack</b>"))
    (is (.contains message "<pre>at "))
    (is (not (.contains message "secret-token")))
    (is (not (.contains message "cookie=secret")))))


(deftest fingerprint-ignores-support-code
  (let [exception (ex-info "same" {})
        base-event {:source :http-request
                    :request-method :get
                    :uri "/admin/orders"
                    :exception exception}]
    (is (= (sentinel/fingerprint
             (assoc base-event :support-code "ERR-AAAA1111"))
           (sentinel/fingerprint
             (assoc base-event :support-code "ERR-BBBB2222"))))))


(deftest notify-deduplicates-and-allows-resend-after-window
  (let [clock (atom 0)
        sent-messages (atom [])
        service (capture-service sent-messages #(deref clock))
        exception (ex-info "boom" {})
        event {:source :http-request
               :request-method :get
               :uri "/admin/orders"
               :exception exception}]
    (sentinel/notify! service event)
    (sentinel/notify! service event)
    (swap! clock + (* 15 60 1000) 1)
    (sentinel/notify! service event)
    (is (= 2 (count @sent-messages)))))


(deftest notify-swallow-send-failures
  (let [service
        (sentinel/build-service
          {:enabled? true
           :app-name "demo"
           :env :prod
           :bot-token "token"
           :chat-id "123"
           :dispatch-fn (fn [task] (task))
           :send-fn (fn [_ _]
                      (throw (ex-info "send failed" {})))
           :now-fn (constantly 0)})]
    (is (nil?
          (sentinel/notify!
            service
            {:source :http-request
             :request-method :get
             :uri "/admin/orders"
             :exception (ex-info "boom" {})})))))
