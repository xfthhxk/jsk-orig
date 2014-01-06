(ns jsk.handler
  "JSK handler"
  (:require
            [jsk.conf :as conf]
            [jsk.routes :as routes]
            [jsk.util :as util]
            [jsk.job :as job]
            [jsk.schedule :as schedule]
            [jsk.workflow :as workflow]
            [jsk.user :as user]
            [jsk.messaging :as msg]
            [cemerick.friend :as friend]
            [cemerick.friend.openid :as openid]
            [compojure.handler :as ch]
            [ring.middleware.edn :as redn]
            [ring.middleware.reload :as reload]
            [ring.util.response :as rr]
            [com.keminglabs.jetty7-websockets-async.core :refer [configurator]]
            [clojure.core.async :refer [chan go go-loop put! >! <!]]
            [taoensso.timbre :as log])
  (:use [ring.middleware.session.memory :only [memory-store]]
        [ring.middleware.resource :only [wrap-resource]]
        [ring.middleware.file-info :only [wrap-file-info]]))



; web socket clients, output channels
(def ws-clients (atom #{}))

; As clients connect they are added to this channel
; how do we know when they disconnect.
(def ws-connect-channel (chan))

; Channel used for messages going to the conductor
(def conductor-write-chan (chan))

(def app-id (util/jvm-instance-name))

;-----------------------------------------------------------------------
; websocket configuration and handling of clients
;-----------------------------------------------------------------------
(def ws-configurator
  (configurator ws-connect-channel {:path "/executions"}))

(defn init-ws [ch]
  (go-loop [{:keys[in out] :as ws-req} (<! ch)]
    (swap! ws-clients conj in)
    (recur (<! ch))))


(defn- broadcast-to-clients [data]
  (let [data-str (pr-str data)]
    (doseq [c @ws-clients]
      (put! c data-str))))


(def last-conductor-hb (atom 0))

(defmulti dispatch :msg)

(defmethod dispatch :default [data]
  (comment "this is a no-op method"))

;-----------------------------------------------------------------------
; Reads status-updates from conductor and forwardst to all websockets
;-----------------------------------------------------------------------
(defn- run-conductor-msg-loop
  "Starts the conductor msg loop"
  [host port]
  (let [sock (msg/make-socket "tcp" host port false :sub)
        topics ["status-updates" "broadcast" app-id]]

    (log/info "Subscribing to conductor topics" topics "on" host ":" port)
    (msg/subscribe sock topics)

    ;  read from pub socket and write to all the clients
    (loop [data (msg/read-pub-data sock)]

      (try
        (if (:event data)
          (broadcast-to-clients data))

        (if (:msg data)
          (dispatch data))

        (catch Exception ex
          (log/error ex)))

      (reset! last-conductor-hb (util/now))

      (recur (msg/read-pub-data sock)))))


;-----------------------------------------------------------------------
; Message processing loop to publish messages to conductor.
;-----------------------------------------------------------------------
(defn- run-conductor-writes [ch host port]
  (let [sock (msg/make-socket "tcp" host port false :pub)
        topic ""]
    (go-loop [msg (<! ch)]
      (try
        (msg/publish sock topic msg)
        (catch Exception ex
          (log/error ex)))
      (recur (<! ch)))))

(defn- time-since-last-hb []
  (- (util/now) @last-conductor-hb))

(defn- ensure-conductor-connection [ch time-ms]
  (while true
    (while (> (time-since-last-hb) time-ms)
      (log/info "Pinging conductor, last msg received" @last-conductor-hb)
      (put! ch {:msg :ping :reply-to app-id})
      (Thread/sleep 1000))
    (Thread/sleep time-ms)))


;-----------------------------------------------------------------------
; App starts ticking here.
;-----------------------------------------------------------------------
(defn init [conductor-host cmd-port req-port]
  "init will be called once when the app is deployed as a servlet
   on an app server such as Tomcat"

  (log/info "JSK web app beginning init.")
  (log/info "Connecting to database.")
  (conf/init-db)

  (log/info "Initializing websockets.")
  (init-ws ws-connect-channel)

  ; schedule or schedule assoc changes need to be published to conductor

  (log/debug "Setting conductor write chan")
  (schedule/init conductor-write-chan)
  (job/init conductor-write-chan)
  (workflow/init conductor-write-chan)

  (log/info "Starting conductor-msg-processor thread.")
  (util/start-thread "conductor-msg-processor"
                     #(run-conductor-msg-loop conductor-host cmd-port))

  (util/start-thread "ensure-conductor-connected"
                     #(ensure-conductor-connection conductor-write-chan (conf/heartbeats-dead-after-ms)))

  (log/info "Initializing publication to conductor.")
  (run-conductor-writes conductor-write-chan conductor-host req-port)

  (log/info "JSK web app init finished."))

;-----------------------------------------------------------------------
; App shutdown procedure.
;-----------------------------------------------------------------------
(defn destroy
  "destroy will be called when the app is shut down"
  []
  (log/info "Destroy called."))



;-----------------------------------------------------------------------
; Exception handling middleware.
; Client communicates via XHRs using EDN, don't want to send back
; a huge stack trace. This logs the error and sends back a 500 response
; with the error message.
;-----------------------------------------------------------------------
(defn- wrap-exception [handler]
  (fn[request]
    (try
      (handler request)
      (catch Exception ex
        (log/error ex)
        (-> [(.getMessage ex)] util/make-error-response routes/edn-response (rr/status 500))))))

;-----------------------------------------------------------------------
; Serve up index.html when nothing specified.
;-----------------------------------------------------------------------
(defn- wrap-dir-index [handler]
  (fn[request]
    (handler (update-in request [:uri] #(if (= "/" %1) "/index.html" %1)))))

;-----------------------------------------------------------------------
; Friend authentication
;-----------------------------------------------------------------------
(defn- login-failure-handler [request]
  (log/error "login failed: " request))


(defn- friend-credential-fn [m]
  (log/debug "friend-credential-fn input map is: " m)
  (if-let [app-user (user/get-by-email (:email m))]
    (assoc m :jsk-user app-user)
    m))


(defn- friend-unauth-handler [request]
  (log/debug "In unauth handler handler")
  (rr/redirect (str (:context request) "/login.html")))

(defn- make-friend-auth [routes]
  (friend/authenticate routes {:allow-anon? false
                               :unauthenticated-handler  friend-unauth-handler
                               :default-landing-uri "/index.html"
                               :login-uri "/login.html"
                               :login-failure-handler login-failure-handler
                               :workflows [(openid/workflow
                                              :openid-uri "/openid/login"
                                              :max-nonce-age (* 1000 60 5) ; 5 minutes in milliseconds
                                              :credential-fn friend-credential-fn)]}))

(def unauth-ring-response (-> ["Unauthenticated."] util/make-error-response routes/edn-response (rr/status 401)))

(defn- send-unauth-ring-response [msg app-user edn?]
  (log/warn "send-unauth-ring: " msg)
  (log/warn "app-user: " app-user)
  (log/warn "edn? " edn?)
  unauth-ring-response)

; friend will redirect, and the xhr will follow the redirect which eventually
; results in a 200 and shows the login page in the xhr response
; this gets to it before friend does and issues a 401 unauth
; which the client can handle
(defn- wrap-api-unauthenticated [handler]
  (fn[request]
    (let [app-user (-> request friend/current-authentication :jsk-user)
          edn? (util/edn-request? request)]
      (if (and edn? (nil? app-user))
        (send-unauth-ring-response "Before calling handler" app-user edn?)
        (let [resp (handler request)
              status (:status resp)]
          (if (and edn? (= 302 status)) ; 302 is redirect ie for login
            (send-unauth-ring-response "After calling handler" app-user edn?)
            resp))))))


(defn- wrap-jsk-user-in-session [handler]
  (fn[request]
    (if-let [app-user (-> request friend/current-authentication :jsk-user)]
      (handler (assoc-in request [:session :jsk-user] app-user))
      (throw (Exception. "Unauthenticated request found! Bad middleware layering likely.")))))



; -- last item happens first
(def app (-> routes/all-routes
             redn/wrap-edn-params
             ;wrap-jsk-user-in-session
             ;make-friend-auth
             ;wrap-api-unauthenticated
             ch/site
             wrap-dir-index
             (wrap-resource "public")
             wrap-file-info
             wrap-exception))

