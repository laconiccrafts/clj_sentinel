(ns clj_sentinel.integrant
  "Optional Integrant adapter for sentinel alert services."
  (:require
    [clj_sentinel.core :as sentinel]
    [integrant.core :as ig]))


(defmethod ig/init-key :sentinel/telegram
  [_ config]
  (sentinel/build-service config))


(defmethod ig/halt-key! :sentinel/telegram
  [_ service]
  (sentinel/shutdown! service))
