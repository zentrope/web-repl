(ns web-repl.core
  ;;
  ;; The idea is that you plug this into your app then use the web-repl
  ;; to inspect and, perhaps, re-define functions.
  ;;
  (:gen-class)
  (:require
    [clojure.core.async :refer [go chan <! put! close!]]
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

(defprotocol ISvc
  (start! [this]
    "Start the service.")
  (stop! [this]
    "Stop the service, resetting all local state."))

(defn- on-jvm-shutdown!
  [f]
  (doto (Runtime/getRuntime)
    (.addShutdownHook (Thread. f))))

(defn- find-port
  []
  (let [s (java.net.ServerSocket. 0)
        port (.getLocalPort s)]
    (.close s)
    port))

;;-----------------------------------------------------------------------------
;; Repl Server

(defrecord ^:private ReplServer [port !server]
  ISvc
  (start! [_]
    (debugf " - starting repl server on port %s." port)
    (reset! !server (start-server :port port)))
  (stop! [_]
    (debug " - stopping repl server.")
    (when-let [server @!server]
      (stop-server server)
      (reset! !server nil))))

(defn- make-repl-server
  [port]
  (ReplServer. port (atom nil)))

;;-----------------------------------------------------------------------------
;; Repl Client

(defprotocol IReplClient
  (send-cmd! [this cmd]
    "Send a command to the repl server."))

(defrecord ^:private ReplClient [port !state]
  ISvc
  (start! [_]
    (debugf " - starting proxy repl client on port %s." port)
    (let [conn (repl/connect :port port)
          client (repl/client conn 10000)
          session (repl/new-session client)]
      (reset! !state {:conn conn :client client :session session})))
  (stop! [_]
    (debug " - stopping proxy repl client.")
    (when-let [conn (:conn @!state)]
      (.close conn))
    (reset! !state {:conn nil :client nil :session nil}))
  IReplClient
  (send-cmd! [_ cmd]
    (let [{:keys [client session]} @!state
        msg (assoc cmd :session session)]
    (->> (repl/message client msg)
         doall))))

(defn- make-repl-client
  [port]
  (ReplClient. port (atom {:conn nil :client nil :session nil})))

;;-----------------------------------------------------------------------------
;; Web Server

(defn- shutdown-monitor
  [channel conn]
  (go
    (<! conn)
    (httpd/close channel)))

(defn- close-handler
  [!conns conn]
  (fn [status]
    (debugf "sock> clos: [%s]." status)
    (swap! !conns disj conn)))

(defn- receive-handler
  [repl-client channel]
  (fn [data]
    (debugf "sock> recv: [%s]." data)
    (let [result (send-cmd! repl-client {:op :eval :code data})]
      (debugf "sock> send << %s >>" (pr-str result))
      (httpd/send! channel (str [:eval-resp result])))))

(defn- socket-handler
  [repl-client !conns]
  (let [conn (chan)]
    (swap! !conns conj conn)
    (fn [request]
      (debug "http> got socket connect")
      (httpd/with-channel request channel
        (shutdown-monitor channel conn)
        (httpd/on-close channel (close-handler !conns conn))
        (httpd/on-receive channel (receive-handler repl-client channel))))))

(defn- main-routes
  [repl-client !conns]
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
     (socket-handler repl-client !conns))
   (route/resources "/")
   (route/not-found "<h1><a href='/'>Here?</a></h1>")))

(defrecord ^:private WebSvc [port repl-client !conns !server]
  ISvc
  (start! [_]
    (debugf " - starting web-app service on port %s." port)
    (let [handlers (fn [r] ((main-routes repl-client !conns) r))
          params {:port port :worker-name-prefix "http-"}
          server (httpd/run-server handlers params)]
      (reset! !server server)))
  ;;
  (stop! [_]
    (debug " - stopping web-app service.")
    (when-let [server @!server]
      ;;
      ;; Close connected sockets.
      ;;
      (doseq [c @!conns]
        (close! c))
      ;;
      ;; Wait a bit.
      ;;
      (Thread/sleep 100)
      (server :timeout 1000)
      (reset! !server nil)
      (reset! !conns #{}))))

(defn- make-web-svc
  [port repl-client]
  (WebSvc. port repl-client (atom #{}) (atom nil)))

;;-----------------------------------------------------------------------------
;; App Lifecycle

(defrecord WebRepl [web-svc repl-svc repl-client]
  ISvc
  (start! [this]
    (debug "Starting Web REPL.")
    (start! web-svc)
    (start! repl-svc)
    (start! repl-client)
    (on-jvm-shutdown! (fn [] (stop! this))))
  (stop! [_]
    (debug "Stopping Web REPL.")
    (stop! repl-client)
    (stop! repl-svc)
    (stop! web-svc)))

(defn make-wrepl
  [port]
  (let [repl-port (find-port)
        repl-client (make-repl-client repl-port)
        repl-server (make-repl-server repl-port)
        web-svc (make-web-svc port repl-client)]
    (WebRepl. web-svc repl-server repl-client)))

;;-----------------------------------------------------------------------------

(defn -main
  "Example usage, I guess."
  [& args]
  (debug "Application bootstrap.")
  (let [lock (promise)
        repl (make-wrepl 1337)]
    ;; (on-jvm-shutdown! (fn [] (deliver lock :done)))
    (start! repl)
    (deref lock)
    (System/exit 0)))
