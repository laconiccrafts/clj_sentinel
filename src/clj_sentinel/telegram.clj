(ns clj_sentinel.telegram
  "Telegram Bot API transport helpers for sentinel alerts."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log])
  (:import
    (java.net
      URI
      URLEncoder)
    (java.net.http
      HttpClient
      HttpRequest
      HttpRequest$BodyPublishers
      HttpResponse$BodyHandlers)))


(def ^:private telegram-api-prefix
  "Telegram Bot API base URL prefix."
  "https://api.telegram.org")


(defn http-client
  "Creates a Java HTTP client for Telegram API calls."
  []
  (HttpClient/newHttpClient))


(defn- form-encode
  "Encodes key/value pairs for x-www-form-urlencoded requests."
  [params]
  (->> params
       (map (fn [[key value]]
              (str (URLEncoder/encode (name key) "UTF-8")
                   "="
                   (URLEncoder/encode (str value) "UTF-8"))))
       (str/join "&")))


(defn send-message!
  "Sends a plain-text Telegram message and logs on non-2xx failures."
  [service text]
  (let [request-body
        (form-encode
          {:chat_id (:chat-id service)
           :disable_web_page_preview true
           :text text})
        request
        (-> (HttpRequest/newBuilder
              (URI/create
                (str telegram-api-prefix
                     "/bot"
                     (:bot-token service)
                     "/sendMessage")))
            (.header "Content-Type"
                     "application/x-www-form-urlencoded")
            (.POST
              (HttpRequest$BodyPublishers/ofString request-body))
            .build)
        response (.send ^HttpClient (:client service)
                        request
                        (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)]
    (when-not (<= 200 status 299)
      (log/warn
        "Telegram alert request failed."
        {:status status
         :response-body
         (some-> (.body response)
                 (subs 0 (min 200 (count (.body response)))))}))))
