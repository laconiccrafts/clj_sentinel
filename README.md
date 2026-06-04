# clj_sentinel

Reusable Clojure alerting primitives with Telegram delivery, Ring
middleware, Integrant wiring, and JVM uncaught-exception helpers.

This library targets apps that want fast error alerts without coupling
alert delivery to a specific web framework or logging backend.

## What It Provides

- async Telegram delivery through the Bot API
- process-local alert deduplication by fingerprint
- sanitized HTML alert formatting for Telegram
- generic Ring middleware for `500+` responses and thrown exceptions
- optional Integrant adapter
- optional JVM uncaught-exception handler

## Install Library

Add `clj_sentinel` to your project `deps.edn`.

```clojure
{:deps
 {laconiccrafts/clj-sentinel
  {:git/url "https://github.com/laconiccrafts/clj_sentinel.git"
   :git/tag "v0.1.1"}}}
```

If your `deps.edn` already has a `:deps` map, add only the
`laconiccrafts/clj-sentinel` entry.

## Basic Usage

Build a service and send alerts directly from your app code.

```clojure
(ns my-app.alerts
  (:require
    [clj_sentinel.core :as sentinel]))

(def alerts
  (sentinel/build-service
    {:enabled? true
     :app-name "my-app"
     :env :prod
     :bot-token (System/getenv "TELEGRAM_BOT_TOKEN")
     :chat-id (System/getenv "TELEGRAM_CHAT_ID")}))

(sentinel/notify!
  alerts
  {:source :worker
   :worker :email-queue
   :stage :deliver
   :exception (ex-info "SMTP failed" {})})
```

Standard event shape:

- required: `:source`, `:exception`
- optional: `:worker`, `:stage`, `:thread-name`, `:request-method`,
  `:uri`, `:support-code`, `:extra-lines`

## Ring Middleware

`clj_sentinel.ring` provides generic middleware for alerting on
exceptions and `500+` responses without changing the response body.

```clojure
(ns my-app.web.middleware
  (:require
    [clj_sentinel.ring :as sentinel.ring]))

(defn wrap-prod-alerts
  [handler alerts]
  (sentinel.ring/wrap-exception-alerts handler alerts))
```

Default behavior:

- alert when a handler throws
- alert when a handler returns `500-599`
- rethrow exceptions after notifying
- keep request-body handling outside the library

If your existing exception layer turns exceptions into responses, attach
the original exception to the response so the alert keeps useful context.

```clojure
(sentinel.ring/annotate-response
  {:status 500
   :body {:message "Internal error"}}
  {:exception exception
   :support-code "ERR-ABCD1234"})
```

## Integrant

`clj_sentinel.integrant` registers `:sentinel/telegram`.

Load the namespace once so Integrant sees the methods.

```clojure
(ns my-app.core
  (:require
    [clj_sentinel.integrant]))
```

Now declare the component in `resources/system.edn`.

```clojure
:sentinel/telegram
#profile {:dev {:enabled? false
                :env #ig/ref :system/env
                :app-name "my-app"
                :dedupe-window-minutes 15
                :stack-frame-limit 8}
          :test {:enabled? false
                 :env #ig/ref :system/env
                 :app-name "my-app"
                 :dedupe-window-minutes 15
                 :stack-frame-limit 8}
          :prod {:enabled? true
                 :env #ig/ref :system/env
                 :app-name "my-app"
                 :dedupe-window-minutes 15
                 :stack-frame-limit 8
                 :bot-token #env TELEGRAM_BOT_TOKEN
                 :chat-id #env TELEGRAM_CHAT_ID}}
```

Supported config keys:

- `:enabled?`
- `:app-name`
- `:env`
- `:bot-token`
- `:chat-id`
- `:dedupe-window-minutes`
- `:stack-frame-limit`

## JVM Uncaught Exceptions

`clj_sentinel.jvm` can forward uncaught thread failures into sentinel.

```clojure
(ns my-app.core
  (:require
    [clj_sentinel.jvm :as sentinel.jvm]))

(defn start-app
  []
  (let [system ...]
    (sentinel.jvm/install-uncaught-exception-handler!
      (:sentinel/telegram system))
    system))

(defn stop-app
  [system]
  (try
    ...
    (finally
      (sentinel.jvm/uninstall-uncaught-exception-handler!))))
```

## Kit Framework

Detailed setup for Kit apps is in
[docs/kit-framework.md](docs/kit-framework.md).

That guide covers:

- installing `clj_sentinel` in a Kit app
- wiring `:sentinel/telegram` in `system.edn`
- adding alert middleware to API and page routes
- installing the uncaught-exception handler in the Kit app lifecycle

## Run Tests

Run all tests:

```bash
clojure -M:test
```

## License

Copyright © 2026 Efraim Gonçalves

Distributed under the MIT License.
