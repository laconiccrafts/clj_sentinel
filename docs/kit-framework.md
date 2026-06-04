# Kit Framework Integration

Detailed setup for Kit applications that want Telegram prod error
alerts with explicit Integrant wiring.

This guide targets:

- generated Kit apps
- apps using Reitit route modules
- apps that want app-level alerting instead of logger appenders

## Prerequisites

This guide assumes your Kit app already boots through Integrant and has
split route modules such as `:reitit.routes/api`, `:reitit.routes/pages`,
or similar.

```bash
neil new io.github.kit-clj/kit yourname/guestbook
```

```bash
clojure -Tclj-new create :template io.github.kit-clj :name yourname/guestbook
```

## 1. Install Library

Add `clj_sentinel` to your project `deps.edn`.

```clojure
{:deps
 {laconiccrafts/clj-sentinel
  {:git/url "https://github.com/laconiccrafts/clj_sentinel.git"
   :git/tag "v0.1.1"}}}
```

If your `deps.edn` already has a `:deps` map, add only the
`laconiccrafts/clj-sentinel` entry.

## 2. Load Integrant Adapter

Load the Integrant namespace once so the `:sentinel/telegram` init
method is available.

```clojure
(ns my-app.core
  (:require
    [clj_sentinel.integrant]
    [clj_sentinel.jvm :as sentinel.jvm]
    [integrant.core :as ig]
    [my-app.config :as config]))
```

## 3. Add Sentinel Component

Add the sentinel component to your app `resources/system.edn`.

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

`TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` are required only when
`:enabled?` is true.

## 4. Wire Alerts Into Route Components

Kit docs pattern is explicit dependency injection: route components
receive the raw dependency and build middleware locally.

API routes:

```clojure
(ns my-app.web.routes.api
  (:require
    [clj_sentinel.ring :as sentinel.ring]
    [my-app.web.middleware.exception :as exception]
    [integrant.core :as ig]))

(defn route-data
  [alerts]
  {:middleware
   [(sentinel.ring/exception-alerts-middleware alerts)
    ...
    (exception/wrap-api-exception)]})

(defmethod ig/init-key :reitit.routes/api
  [_ {:keys [alerts base-path] :as opts}]
  (fn []
    [base-path
     (route-data alerts)
     (api-routes opts)]))
```

`system.edn`:

```clojure
:reitit.routes/api
{:base-path "/api"
 :alerts #ig/ref :sentinel/telegram
 ...}
```

Page routes:

```clojure
(ns my-app.web.routes.pages
  (:require
    [my-app.web.middleware.auth :as auth-middleware]
    [my-app.web.middleware.exception :as exception]
    [my-app.web.routes.pages.shared :as shared]
    [integrant.core :as ig]))

(defn route-data
  [conn turnstile alerts]
  {:middleware
   [(shared/wrap-page-defaults)
    ...
    (exception/page-coerce-exceptions-middleware alerts)
    ...
    (shared/wrap-turnstile-context turnstile)
    (auth-middleware/wrap-current-user conn)
    (exception/wrap-page-exception alerts)]})

(defmethod ig/init-key :reitit.routes/pages
  [_ {:keys [alerts base-path conn turnstile] :as opts}]
  (fn []
    [base-path
     (route-data conn turnstile alerts)
     (page-routes opts)]))
```

`system.edn`:

```clojure
:reitit.routes/pages
{:base-path ""
 :alerts #ig/ref :sentinel/telegram
 :conn #ig/ref :db.conn/wrapped
 :turnstile #ig/ref :security/turnstile
 ...}
```

This is intentionally explicit. The route component owns middleware
construction, and Integrant provides the concrete deps.

## 5. Annotate Existing Exception Responses

If your app already turns exceptions into Ring responses, keep that
behavior and attach the original exception so `clj_sentinel.ring` can
alert with useful context.

```clojure
(ns my-app.web.middleware.exception
  (:require
    [clj_sentinel.ring :as sentinel.ring]))

(defn- api-handler
  [_alerts message status exception request]
  (sentinel.ring/annotate-response
    {:status status
     :body {:message message
            :uri (:uri request)}}
    {:exception exception}))
```

For HTML pages, you can also attach app-specific fields such as a
support code.

```clojure
(sentinel.ring/annotate-response
  (layout/error-page request
    {:status 500
     :support-code support-code})
  {:exception exception
   :support-code support-code})
```

## 6. Install JVM Uncaught Handler

Hook sentinel into your Kit app lifecycle so uncaught thread failures
also alert.

```clojure
(defonce system (atom nil))

(defn start-app
  [& [params]]
  (let [started-system
        (->> (config/system-config (or (:opts params) {}))
             ig/expand
             ig/init)]
    (sentinel.jvm/install-uncaught-exception-handler!
      (:sentinel/telegram started-system))
    (reset! system started-system)))

(defn stop-app
  []
  (try
    (when-let [running-system @system]
      (ig/halt! running-system)
      (reset! system nil))
    (finally
      (sentinel.jvm/uninstall-uncaught-exception-handler!))))
```

## 7. Alert From Background Workers

Background code can call `notify!` directly.

```clojure
(ns my-app.jobs
  (:require
    [clj_sentinel.core :as sentinel]))

(defn process-job!
  [alerts job]
  (try
    ...
    (catch Exception ex
      (sentinel/notify!
        alerts
        {:source :worker
         :worker :job-runner
         :stage :process-job
         :exception ex
         :uri (str "job:" (:id job))})
      (throw ex))))
```

## 8. Run And Verify

Start your Kit REPL and boot the app.

Where this command runs: terminal inside your Kit app.

```bash
clj -M:dev
```

Where this code goes: REPL inside your Kit app.

```clojure
(go)
```

Then verify behavior:

- trigger an API `500` and confirm one Telegram alert arrives
- trigger a page `500` and confirm support-code context appears if you
  attach it
- trigger a background worker failure and confirm worker/stage metadata
  appears
- confirm `400` request-coercion or business errors do not alert unless
  you intentionally classify them as alertable

## Reference Notes

This guide matches current `clj_sentinel` behavior:

- Telegram delivery is best-effort and async
- dedupe is process-local in memory
- Ring middleware is generic and response-preserving
- Integrant support is optional
- apps remain responsible for their own redaction policy and alert
  classification
