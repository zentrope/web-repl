(ns web-repl.main
  (:require-macros
    [cljs.core.async.macros :refer [go-loop]])
  (:require
    [cljs.core.async :refer [put! chan]]
    [clojure.string :refer [trim]]
    [cljs.reader :as reader][om.core :as om :include-macros true]
    [sablono.core :as html :refer-macros [html]]))

(enable-console-print!)

;;-----------------------------------------------------------------------------
;; WEB SOCKET
;;-----------------------------------------------------------------------------

(def ^:private ws-url
  (str "ws://"
       (.-hostname (.-location js/window))
       ":"
       (.-port (.-location js/window))
       "/ws"))

(defn init-socket!
  [queue]
  (let [ws (js/WebSocket. ws-url)]
    (set! (.-onerror ws) #(put! queue [:socket-error %]))
    (set! (.-onmessage ws) #(put! queue (reader/read-string (.-data %))))
    (set! (.-onclose ws) #(put! queue [:socket-close {}]))
    (set! (.-onopen ws) #(put! queue [:socket-open {}]))
    ws))

(defn close-socket!
  [ws]
  (set! (.-onclose ws) nil)
  (.-close ws))

(defn send
  [ws msg]
  (try
    (.send ws msg)
    (catch js/Error e
      (.log js/console "socket-send-err:" e))))

;;-----------------------------------------------------------------------------
;; DOM Helpers
;;-----------------------------------------------------------------------------

(defn- q
  [owner]
  (om/get-shared owner :queue))

(defn- focus!
  [owner ref]
  (.focus (om/get-node owner ref)))

(defn- value-of
  [owner ref]
  (.-value (om/get-node owner ref)))

(defn- arrest!
  [e]
  (.stopPropagation e)
  (.preventDefault e))

(defn- key-handler
  [owner e ref]
  (let [shift? (.-shiftKey e)
        enter? (= (.-keyCode e) 13)
        escape? (= (.-keyCode e) 27)]
    (when escape?
      (arrest! e)
      (om/set-state! owner :expr ""))
    (when (and shift? enter?)
      (arrest! e)
      (put! (q owner) [:eval-code (value-of owner ref)]))))

;;-----------------------------------------------------------------------------
;; View Components
;;-----------------------------------------------------------------------------

(defn- name-space-component
  [{:keys [namespace] :as state} owner]
  (om/component
   (html
    [:header
     [:div.namespace (str "(ns " namespace ")")]
     [:div.actions
      [:button {:onClick #(put! (q owner) [:clear-transcript {}])} "clear"]]])))

(defn- transcript-component
  [{:keys [transcript] :as state} owner]
  (reify
    om/IDidUpdate
    (did-update [_ _ _]
      (let [c (.-children (om/get-node owner))
            l (alength c)
            n (aget c (dec l))]
        (when (> l 0)
          (.scrollIntoView n true))))
    om/IRender
    (render [_]
      (html
       [:section#transcript
        (for [{:keys [type item ns]} transcript]
          (case type
            :code [:div.code
                   [:table
                    [:tr
                     [:td [:span.ns-place ns]]
                     [:td [:span.ns-prompt " => "]]
                     [:td [:pre.ns-value {:onDoubleClick
                                          #(put! (q owner) [:repl-text item])}
                           item]]]]]
            :value [:div.value item]
            :stdout [:div.stdout [:pre item]]
            :error [:div.error item]
            [:div.unknown (str [type item])]))]))))

(defn- reader-component
  [{:keys [repl-text] :as state} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:orig repl-text
       :expr repl-text})
    om/IDidUpdate
    (did-update [_ _ prev-state]
      (when (not= repl-text (:orig prev-state))
        (om/set-state! owner :expr repl-text)
        (om/set-state! owner :orig repl-text)
        (focus! owner "repl-in")))
    om/IDidMount
    (did-mount [_]
      (focus! owner "repl-in"))
    om/IRender
    (render [_]
      (html
       [:section#reader
        [:section#input
         [:textarea#repl-input
          {:ref "repl-in"
           :onChange #(om/set-state! owner :expr (value-of owner "repl-in"))
           :value (om/get-state owner :expr)
           :defaultValue repl-text
           :onKeyDown #(key-handler owner % "repl-in")}]]]))))

(defn- mini-buffer-component
  [{:keys [message] :as state} owner]
  (om/component
   (html
    [:section#minibuf
     [:p message]])))

(defn- frame-component
  [state owner]
  (om/component
   (html
    [:section#container
     (om/build name-space-component state)
     (om/build transcript-component state)
     (om/build reader-component state)
     (om/build mini-buffer-component state)])))

(defn install-root-view!
  [app-state eventq]
  (om/root frame-component app-state {:target js/document.body
                                      :shared {:queue eventq}}))

;;-----------------------------------------------------------------------------
;; State Management
;;-----------------------------------------------------------------------------

(def app-state
  ;;
  ;; Just store the responses one after another and allow the OM / VIEW
  ;; stuff to figure out how to draw it on the screen.
  ;;
  (atom {:message "Ready."
         :repl-text ";; Start\n\n."
         :namespace "user"
         :transcript []}))

(def web-socket (atom nil))

(defn- set-namespace!
  [!state namespace]
  (swap! !state assoc :namespace namespace))

(defn- set-message!
  [!state msg]
  (swap! !state assoc :message msg))

(defn- set-repl-text!
  [!state text]
  (swap! !state assoc :repl-text text))

(defn- append-transcript!
  [!state type value & [namespace]]
  (let [item (cond-> {:type type :item value}
                     (not (nil? namespace)) (assoc :ns namespace))]
    (swap! !state update-in [:transcript] conj item)))

(defn- handle-evaluation-response!
  [!state responses]
  (set-message! !state "Ready.")
  (doseq [response responses]
    (when-let [ns (:ns response)]
      (set-namespace! !state ns))
    (when (contains? response :out)
      (append-transcript! !state :stdout (:out response)))
    (when (contains? response :err)
      (append-transcript! !state :error (:err response)))
    (when (contains? response :value)
      (append-transcript! !state :value (:value response)))))

(defn- handle-evaluation-request!
  [!state request]
  (set-message! !state "Evaluating...")
  (append-transcript! !state :code (trim request) (:namespace @!state))
  (set-repl-text! !state request)
  (send @web-socket (trim request)))

(defn- handle-clear-transcript!
  [!state]
  (swap! !state assoc :transcript [])
  (swap! !state assoc :repl-text ""))

;;-----------------------------------------------------------------------------
;; Application Event Management
;;-----------------------------------------------------------------------------

(defn- apply-msg!
  [!state queue topic msg]
  (case topic
    :clear-transcript (handle-clear-transcript! !state)
    :eval-code (handle-evaluation-request! !state msg)
    :eval-resp (handle-evaluation-response! !state msg)
    :repl-text (set-repl-text! !state msg)
    :socket-open (handle-evaluation-request! !state ":whoo-hoo!")
    :noop))

(defn- app-loop!
  [!state queue]
  (go-loop []
    (when-let [[topic msg] (<! queue)]
;;      (println "msg>" (pr-str [topic msg]))
      (try
        (apply-msg! !state queue topic msg)
        (catch js/Error e
          (println "err>" (pr-str e))))
      (recur))))

(defn- main
  []
  (let [queue (chan)
        ws (init-socket! queue)]
    (reset! web-socket ws)
    (app-loop! app-state queue)
    (install-root-view! app-state queue)))

(set! (.-onload js/window) main)
