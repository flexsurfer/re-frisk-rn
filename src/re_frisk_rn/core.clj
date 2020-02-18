(ns re-frisk-rn.core
  (:use compojure.core)
  (:require
    [clojure.set :as set]
    [org.httpkit.server :as ohs]
    [compojure.route :as route]
    [taoensso.sente :as sente]
    [taoensso.sente.packers.transit :as sente-transit]
    [taoensso.sente.server-adapters.http-kit :refer (get-sch-adapter)]
    [re-frisk-rn.defaults :refer [wrap-defaults site-defaults]]
    [ring.middleware.cors :refer [wrap-cors]]
    [ring.util.response :as response]
    [re-frisk.delta :as delta]))

(defonce app-db (atom nil))
(defonce id-handler (atom nil))
;; Contents of the last :refrisk/pre-events message. If a client connects
;; between :refrisk/pre-events and :refrisk/events, then this data is sent to it
;; to resynchronise, as :refrisk/events message won't contain the event
;; contents.
(defonce pending-event (atom nil))

;; The list of clients who are interested in receiving notifications.
(defonce listeners (atom #{}))
;; Current re-frisk-remote connection
(defonce remote-conn (atom nil))

(let [{:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket-server!
        (get-sch-adapter) {:packer (sente-transit/get-transit-packer)
                           :csrf-token-fn nil
                           :user-id-fn :client-id})]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids)) ; Watchable, read-only atom

;; Remove disconnected clients from the set of listeners
(add-watch
 connected-uids nil
 (fn [_ _ old new]
   (let [removed (set/difference (:any old) (:any new))]
     (swap! listeners set/difference removed)
     (when (contains? removed @remote-conn)
       (reset! remote-conn nil)))))

(defn- classify-change [old new]
  (cond
    (and (empty? old) (not (empty? new))) :first-added
    (and (not (empty? old)) (empty? new)) :last-removed
    :else :other))

(add-watch
 listeners nil
 (fn [_ _ old new]
   (when @remote-conn
     (case (classify-change old new)
       :first-added (chsk-send! @remote-conn [:refrisk/enable nil])
       :last-removed (chsk-send! @remote-conn [:refrisk/disable nil])
       :other nil))))

;HANDLERS
(defmulti -event-msg-handler "Multimethod to handle Sente `event-msg`s" :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler :default
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]))

;RE-FRISK HANDLERS
(defmethod -event-msg-handler :refrisk/app-db
  [{:keys [?data]}]
  (reset! app-db ?data)
  (doseq [uid @listeners]
    (chsk-send! uid [:refrisk/app-db @app-db])))

(defmethod -event-msg-handler :refrisk/app-db-delta
  [{:keys [?data]}]
  (swap! app-db delta/apply ?data)
  (doseq [uid @listeners]
    (chsk-send! uid [:refrisk/app-db @app-db])))

(defmethod -event-msg-handler :refrisk/events
  [{:keys [?data]}]
  (reset! pending-event nil)
  (doseq [uid @listeners]
    (chsk-send! uid [:refrisk/events ?data])))

(defmethod -event-msg-handler :refrisk/pre-events
  [{:keys [?data]}]
  (reset! pending-event ?data)
  (doseq [uid @listeners]
    (chsk-send! uid [:refrisk/pre-events ?data])))

(defmethod -event-msg-handler :refrisk/id-handler
  [{:keys [?data]}]
  (reset! id-handler ?data)

  (doseq [uid @listeners]
    (chsk-send! uid [:refrisk/id-handler @id-handler])))

(defmethod -event-msg-handler :refrisk/id-handler-delta
  [{:keys [?data]}]
  (swap! id-handler delta/apply ?data)
  (doseq [uid @listeners]
    (chsk-send! uid [:refrisk/id-handler @id-handler])))

(defn- sidecar-client-connected [client-id]
  (chsk-send! client-id [:refrisk/app-db @app-db])
  (chsk-send! client-id [:refrisk/id-handler @id-handler])
  (when @pending-event
    (chsk-send! client-id [:refrisk/pre-events @pending-event]))
  (swap! listeners conj client-id))

(defn- remote-connected [client-id]
  (reset! remote-conn client-id)
  (when (pos? (count @listeners))
    (chsk-send! @remote-conn [:refrisk/enable nil])))

(defmethod -event-msg-handler :chsk/uidport-open [{:keys [client-id ring-req]}]
  (let [kind (get-in ring-req [:params :kind])]
    (cond
      (= kind "re-frisk-sidecar") (sidecar-client-connected client-id)
      (= kind "re-frisk-remote") (remote-connected client-id))))

(defmethod -event-msg-handler :trace/log
  [{:as ev-msg :keys [?reply-fn ?data]}]
  (let [uids (:any @connected-uids)]
    (doseq [uid uids]
      (chsk-send! uid [:trace/log ?data]))))

;SENTE ROUTER
(defonce router_ (atom nil))

(defn stop-router! []
  (when-let [stop-fn @router_] (stop-fn)))

(defn start-router! []
  (stop-router!)
  (reset! router_
          (sente/start-server-chsk-router!
            ch-chsk event-msg-handler)))
;ROUTES
(defroutes
  app-routes
  (GET "/" req (response/content-type
                 (response/resource-response "public/re-frisk.html")
                 "text/html"))
  (GET "/10x" req (response/content-type
                   (response/resource-response "public/10x.html")
                   "text/html"))
  (GET  "/chsk" req (ring-ajax-get-or-ws-handshake req))
  (POST "/chsk" req (ring-ajax-post                req))
  (route/resources "/")
  (route/not-found "Not Found"))

;ENTRY POINT
(defn -main [& [port]]
  (start-router!)
  (let [port' (Integer/parseInt (or port "4567"))]
    (ohs/run-server (-> app-routes
                        (wrap-defaults site-defaults)
                        (wrap-cors
                          :access-control-allow-origin #".*"
                          :access-control-allow-methods [:get :put :post :delete]
                          :access-control-allow-credentials "true"))
                    {:port port'})
    (println (str "re-frisk server has been started at http://localhost:" port'))))

(defn start [& [port]]
  (-main port))