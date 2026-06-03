(ns clj_sentinel.core
  "Framework-agnostic alert service with Telegram delivery support."
  (:require
    [clj_sentinel.telegram :as telegram]
    [clojure.string :as str]
    [clojure.tools.logging :as log])
  (:import
    (java.time
      Instant)
    (java.util.concurrent
      ExecutorService
      Executors
      TimeUnit)))


(def ^:private default-app-name
  "Default app name shown in alert messages."
  "unknown-app")


(def ^:private default-dedupe-window-minutes
  "Default deduplication window for repeated alerts."
  15)


(def ^:private default-stack-frame-limit
  "Default number of stack frames included in alert messages."
  8)


(defn- blank->nil
  "Returns nil for blank strings and the value otherwise."
  [value]
  (when-not (str/blank? (str value))
    value))


(defn- require-enabled-value
  "Returns a required enabled setting or throws an ex-info."
  [enabled? field-name value]
  (if-not enabled?
    value
    (or (blank->nil value)
        (throw
          (ex-info
            (str field-name " must be configured when sentinel alerts "
                 "are enabled.")
            {:field-name field-name})))))


(defn- first-stack-frame
  "Returns the first stack frame summary for the given exception."
  [^Throwable exception]
  (when-let [^StackTraceElement frame (first (.getStackTrace exception))]
    (str (.getClassName frame)
         "."
         (.getMethodName frame)
         ":"
         (.getLineNumber frame))))


(defn stack-lines
  "Returns the first `limit` stack frames as compact text lines."
  [^Throwable exception limit]
  (->> (.getStackTrace exception)
       (take limit)
       (mapv (fn [^StackTraceElement frame]
               (str "at "
                    (.getClassName frame)
                    "."
                    (.getMethodName frame)
                    ":"
                    (.getLineNumber frame))))))


(defn fingerprint
  "Builds a stable fingerprint for alert deduplication."
  [{:keys [exception request-method source stage thread-name uri worker]}]
  (str/join "|"
            (remove str/blank?
                    [(some-> source name)
                     (some-> worker name)
                     (some-> stage name)
                     (some-> request-method name)
                     (or uri "")
                     (some-> exception class .getName)
                     (or (first-stack-frame exception) "")
                     (or thread-name "")])))


(defn format-message
  "Formats a sanitized plain-text alert message."
  [service
   {:keys [exception extra-lines fingerprint request-method source stage
           support-code thread-name timestamp uri worker]}]
  (let [stack-frame-limit (:stack-frame-limit service)
        exception-class (some-> exception class .getName)
        exception-message (or (some-> exception ex-message blank->nil)
                              "<no-message>")
        lines
        (concat
          [(str "app=" (:app-name service))
           (str "env=" (name (:env service)))
           (str "source=" (name source))
           (str "timestamp=" timestamp)
           (str "fingerprint=" fingerprint)
           (str "exception=" exception-class)
           (str "message=" exception-message)]
          (when worker
            [(str "worker=" (name worker))])
          (when stage
            [(str "stage=" (name stage))])
          (when thread-name
            [(str "thread=" thread-name)])
          (when (and request-method uri)
            [(str "request=" (-> request-method name str/upper-case)
                  " "
                  uri)])
          (when support-code
            [(str "support-code=" support-code)])
          extra-lines
          ["stack:"]
          (stack-lines exception stack-frame-limit))]
    (str/join "\n" lines)))


(defn- expire-fingerprints!
  "Drops expired fingerprints from the in-memory dedupe cache."
  [service now-ms]
  (let [cutoff (- now-ms (:dedupe-window-ms service))]
    (swap! (:seen-fingerprints service)
           (fn [seen]
             (into {}
                   (filter (fn [[_ seen-at]] (> seen-at cutoff)))
                   seen)))))


(defn- reserve-fingerprint!
  "Marks a fingerprint as seen when it is allowed to send."
  [service alert-fingerprint now-ms]
  (expire-fingerprints! service now-ms)
  (let [seen-fingerprints (:seen-fingerprints service)
        existing (get @seen-fingerprints alert-fingerprint)
        fresh? (or (nil? existing)
                   (>= (- now-ms existing)
                       (:dedupe-window-ms service)))]
    (when fresh?
      (swap! seen-fingerprints assoc alert-fingerprint now-ms))
    fresh?))


(defn notify!
  "Queues an alert for best-effort asynchronous delivery."
  [service {:keys [exception] :as event}]
  (when (and (map? service) (:enabled? service) exception)
    (let [now-ms ((:now-fn service))
          event-fingerprint (fingerprint event)
          enriched-event
          (assoc event
                 :fingerprint event-fingerprint
                 :timestamp (str (Instant/ofEpochMilli now-ms)))]
      (when (reserve-fingerprint! service event-fingerprint now-ms)
        (let [dispatch-fn (:dispatch-fn service)
              send-fn (:send-fn service)]
          (try
            (dispatch-fn
              (fn []
                (try
                  (send-fn service (format-message service enriched-event))
                  (catch Exception send-ex
                    (log/warn send-ex
                              "Sentinel alert delivery failed."
                              {:fingerprint event-fingerprint
                               :source (:source event)})))))
            (catch Exception dispatch-ex
              (log/warn dispatch-ex
                        "Sentinel alert dispatch failed."
                        {:fingerprint event-fingerprint
                         :source (:source event)}))))))))


(defn build-service
  "Builds an alert service map from generic config."
  [{:keys [app-name bot-token chat-id client dedupe-window-minutes
           dispatch-fn enabled? env executor now-fn send-fn
           stack-frame-limit]}]
  (let [enabled? (true? enabled?)
        app-name (or (blank->nil app-name) default-app-name)
        dedupe-window-minutes
        (or dedupe-window-minutes default-dedupe-window-minutes)
        stack-frame-limit
        (or stack-frame-limit default-stack-frame-limit)
        executor (or executor
                     (when enabled?
                       (Executors/newSingleThreadExecutor)))
        dispatch-fn (or dispatch-fn
                        (if executor
                          (fn [task]
                            (.submit ^ExecutorService executor ^Runnable task))
                          (fn [_task] nil)))
        client (or client
                   (when enabled?
                     (telegram/http-client)))
        send-fn (or send-fn telegram/send-message!)]
    {:app-name app-name
     :bot-token
     (require-enabled-value enabled? "TELEGRAM_BOT_TOKEN" bot-token)
     :chat-id
     (require-enabled-value enabled? "TELEGRAM_CHAT_ID" chat-id)
     :client client
     :dedupe-window-ms (* dedupe-window-minutes 60 1000)
     :dispatch-fn dispatch-fn
     :enabled? enabled?
     :env (or env :unknown)
     :executor executor
     :now-fn (or now-fn #(System/currentTimeMillis))
     :seen-fingerprints (atom {})
     :send-fn send-fn
     :stack-frame-limit stack-frame-limit}))


(defn shutdown!
  "Stops any executor owned by the service."
  [{:keys [executor]}]
  (when executor
    (.shutdownNow ^ExecutorService executor)
    (.awaitTermination ^ExecutorService executor 5 TimeUnit/SECONDS)))
