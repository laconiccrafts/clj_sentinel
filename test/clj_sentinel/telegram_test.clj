(ns clj_sentinel.telegram-test
  (:require
    [clj_sentinel.telegram :as telegram]
    [clojure.test :refer :all]))


(deftest telegram-message-body-uses-html-parse-mode
  (let [body (telegram/telegram-message-body
               {:chat-id "123"}
               "<b>boom</b> & details")]
    (is (.contains body "chat_id=123"))
    (is (.contains body "disable_web_page_preview=true"))
    (is (.contains body "parse_mode=HTML"))
    (is (.contains body "text=%3Cb%3Eboom%3C%2Fb%3E+%26+details"))))
