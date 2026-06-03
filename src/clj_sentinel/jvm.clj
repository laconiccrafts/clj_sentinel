(ns clj_sentinel.jvm
  "JVM-level uncaught exception alert helpers."
  (:require
    [clj_sentinel.core :as sentinel]
    [clojure.tools.logging :as log]))


(def ^:private uncaught-alert-service
  "Holds the alert service used by the current uncaught handler."
  (atom nil))


(def ^:private previous-uncaught-handler
  "Stores the uncaught handler active before sentinel installs its own."
  (atom nil))


(defn- log-uncaught-exception!
  "Logs an uncaught exception using a generic application format."
  [thread ex]
  (log/error {:what :uncaught-exception
              :exception ex
              :where (str "Uncaught exception on " (.getName thread))}))


(defn- uncaught-exception-handler
  "Logs uncaught exceptions and forwards them to sentinel alerts."
  [thread ex]
  (log-uncaught-exception! thread ex)
  (sentinel/notify!
    @uncaught-alert-service
    {:source :uncaught-thread
     :thread-name (.getName thread)
     :exception ex}))


(defn install-uncaught-exception-handler!
  "Installs the runtime uncaught exception handler."
  [alert-service]
  (reset! uncaught-alert-service alert-service)
  (when (nil? @previous-uncaught-handler)
    (reset! previous-uncaught-handler
            (Thread/getDefaultUncaughtExceptionHandler)))
  (Thread/setDefaultUncaughtExceptionHandler
    uncaught-exception-handler))


(defn uninstall-uncaught-exception-handler!
  "Restores the pre-start uncaught exception handler."
  []
  (reset! uncaught-alert-service nil)
  (Thread/setDefaultUncaughtExceptionHandler
    @previous-uncaught-handler))
