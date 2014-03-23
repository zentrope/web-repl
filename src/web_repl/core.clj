(ns web-repl.core
  ;;
  ;; The idea is that you plug this into your app then use the web-repl
  ;; to inspect and, perhaps, re-define functions.
  ;;
  (:gen-class)
  (:require
    [clojure.core.async :refer [go-loop]]
    [clojure.tools.nrepl.server :refer [start-server stop-server]]
    [clojure.tools.nrepl :as repl]
    [clojure.tools.logging :refer [debug debugf]]
    [clojure.edn :as edn]
    [compojure.core :refer [routes GET]]
    [compojure.route :as route]
    [hiccup.page :refer [html5 include-css include-js]]
    [org.httpkit.server :as httpd]
    [clojure.pprint :refer [pprint]]))

;;-----------------------------------------------------------------------------
;; Repl Server

(def repl-port 64444)
(def repl-server (atom nil))

(defn start-server!
  []
  (debugf " - starting repl server on port %s." repl-port)
  (if (nil? @repl-server)
    (do (reset! repl-server (start-server :port repl-port))
        :started)
    (do (debug "Please stop server first.")
        :not-started)))

(defn stop-server!
  []
  (debug " - stopping repl server.")
  (when-let [s @repl-server]
    (stop-server s)
    (reset! repl-server nil)
    :stopped))

;;-----------------------------------------------------------------------------
;; Repl Client

(def rc (atom {:conn nil
               :client nil
               :session nil}))

(defn send-cmd
  [!rc cmd]
  (let [{:keys [client session]} @!rc
        msg (assoc cmd :session session)]
    (->> (repl/message client msg)
         ;; (repl/combine-responses)
         doall)))

(defn start-client!
  []
  (debug " - starting proxy repl client.")
  (let [conn (repl/connect :port repl-port)
        client (repl/client conn 10000)
        session (repl/new-session client)]
    (swap! rc assoc :conn conn :client client :session session)))

(defn stop-client!
  []
  (debug " - stopping proxy repl client.")
  (when-let [conn (:conn @rc)]
    (.close conn)
    (reset! rc {:conn nil :client nil :session nil}))
  :client-stopped)

;;-----------------------------------------------------------------------------
;; Web Server

(defn- socket-handler
  []
  (fn [request]
    (debug "http> got socket connect")
    (httpd/with-channel request channel
      (httpd/on-close channel
        (fn [status]
          (debugf "sock> clos: [%s]." status)))
      (httpd/on-receive channel
        (fn [data]
          (debugf "sock> recv: [%s]." data)
          (let [result (send-cmd rc {:op :eval :code data})]
            (debugf "sock> send << %s >>" (pr-str result))
            (httpd/send! channel (str [:eval-resp result]))
            ))))))

(defn- main-routes
  []
  (routes
   (GET "/"
     []
     (html5 [:head
             [:title "REPL"]
             [:meta {:charset "utf-8"}]
             [:meta {:http-quiv "X-UA-Compatible" :content "IE=edge"}]
             [:link {:rel "shortcut icon" :href "favicon.ico"}]
             (include-css "main.css")
             (include-js "main.js")]
            [:body "Loading..."]))
   (GET "/ws"
     []
     (socket-handler))
   (route/resources "/")
   (route/not-found "<h1><a href='/'>Here?</a></h1>")))

(def ^:private web-server (atom nil))

(defn start-webapp!
  []
  (debug " - starting web-app service.")
  (let [handlers (fn [r] ((main-routes) r))
        params {:port 1337 :worker-name-prefix "http-"}
        server (httpd/run-server handlers params)]
    (reset! web-server server)))

(defn stop-webapp!
  []
  (debug " - stopping web-app service.")
  (if-let [s @web-server]
    (do (s)
        (reset! web-server nil)
        :stopped)
    :not-running))

;;-----------------------------------------------------------------------------
;; App Lifecycle

(defn- on-jvm-shutdown!
  [f]
  (doto (Runtime/getRuntime)
    (.addShutdownHook (Thread. f))))

(defn start!
  []
  (debug "Starting application.")
  (start-server!)
  (start-client!)
  (start-webapp!))

(defn stop!
  []
  (debug "Stopping application.")
  (stop-webapp!)
  (stop-client!)
  (stop-server!))

;;-----------------------------------------------------------------------------

(defn -main
  "Example usage, I guess."
  [& args]
  (debug "Application bootstrap.")
  (let [lock (promise)]
    (on-jvm-shutdown! (fn []
                        (stop!)))
    (on-jvm-shutdown! (fn []
                        (debug "Application shutdown request.")
                        (deliver lock :done)))
    (start!)
    (deref lock)))
