(ns glam.server.service
  (:require
    [clojure.pprint :refer [pprint]]
    [ring.util.request :as ring-req]
    [clojure.string :as string]
    [com.fulcrologic.fulcro.server.api-middleware :refer [handle-api-request]]
    [com.fulcrologic.guardrails.core :refer [>defn => | ?]]
    [hiccup.page :refer [html5]]
    [io.pedestal.http :as http]
    [io.pedestal.interceptor :as interceptor]
    [io.pedestal.http.csrf :refer [anti-forgery-token-str]]
    [muuntaja.core :as muu]
    [reitit.http :as rhttp]
    ;; needed for specs
    reitit.http.coercion
    [reitit.http.interceptors.exception :as exception]
    [reitit.http.interceptors.multipart :as multipart]
    [reitit.http.interceptors.muuntaja :as muuntaja]
    [reitit.http.interceptors.parameters :as parameters]
    [reitit.pedestal :as rpedestal]
    [glam.server.config :refer [config]]
    [glam.server.pathom-parser :refer [parser]]
    [glam.server.crux :refer [crux-node crux-session-node]]
    [taoensso.timbre :as log]
    [clojure.java.io :as io]
    [clojure.tools.reader.edn :as edn]
    [crux.api :as crux]
    [glam.crux.common :as cutil])
  (:import (java.io PushbackReader IOException)
           (ring.middleware.session.store SessionStore)
           (java.util UUID)))

;; crux ring session store, from dvingo

(defn make-session-data
  [key data]
  (-> data
      (assoc :__anti-forgery-token (get data anti-forgery-token-str))
      (dissoc anti-forgery-token-str)
      (assoc :crux.db/id key ::session? true)))

(deftype CruxSessionStore [crux-node]
  SessionStore

  (read-session [this key]
    (if (some? key)
      (try
        (let [sess (crux/entity (crux/db crux-node) (UUID/fromString key))]
          (-> sess
              (assoc anti-forgery-token-str (:__anti-forgery-token sess))
              (dissoc :__anti-forgery-token)))
        (catch Exception e
          (log/error "Invalid session. Error reading crux/entity for key: " key)
          {}))
      {}))

  (write-session [_ key data]
    ;(log/info "writing session: at key: " key)
    (let [key     (try (cond-> key (some? key) UUID/fromString)
                       (catch Exception e (UUID/randomUUID)))
          key     (or key (UUID/randomUUID))
          tx-data (make-session-data key data)]
      (log/trace "Writing session data: " tx-data)
      (log/trace "At key : " key)
      (cutil/put crux-node tx-data)
      key))

  (delete-session [_ key]
    (log/info "Deleting session: " key)
    (cutil/delete crux-node key)
    nil))

(defn crux-session-store [crux-node]
  (CruxSessionStore. crux-node))
;; end ring session store

(def manifest-file "public/js/main/manifest.edn")

(defn load-edn
  "Load edn from an io/reader source
  Tries to read as resource first then filename."
  [source]
  (try
    (with-open [r (io/reader (io/resource source))] (edn/read (PushbackReader. r)))
    (catch IOException e
      (try
        ;; try just a file read
        (with-open [r (io/reader source)] (edn/read (PushbackReader. r)))
        (catch IOException e
          (printf "Couldn't open '%s': %s\n" source (.getMessage e)))
        (catch RuntimeException e
          (printf "Error parsing edn file '%s': %s\n" source (.getMessage e))))
      (printf "Couldn't open '%s': %s\n" source (.getMessage e)))
    (catch RuntimeException e
      (printf "Error parsing edn file '%s': %s\n" source (.getMessage e)))))

(defn get-js-filename []
  (:output-name (first (load-edn manifest-file))))

;; ================================================================================
;; Dynamically generated HTML. We do this so we can safely embed the CSRF token
;; in a js var for use by the client, and to use hashed filename output from shadow.
;; ================================================================================
(defn index [csrf-token]
  (if-let [js-filename (get-js-filename)]
    (do (log/debug "Serving index.html")
        (html5
          [:head {:lang "en"}
           [:title "Application"]
           [:meta {:charset "utf-8"}]
           [:meta {:name "viewport" :content "width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no"}]
           [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css?family=Roboto:300,400,500,700&display=swap"}]
           [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/icon?family=Material+Icons"}]
           [:link {:rel "shortcut icon" :href "data:image/x-icon;," :type "image/x-icon"}]
           [:script (str "var fulcro_network_csrf_token = '" csrf-token "';")]]
          [:body
           [:div#app]
           [:script {:src (str "/js/main/" js-filename)}]]))
    (throw (Exception. (str "Error reading JavaScript filename from shadow-cljs manifest.edn file.
    The filename is nil, you probably need to wait for the shadow-cljs build to complete or start it again.
    Check manifest.edn in the shadow-cljs build directory.")))))

(>defn html-response
  [html]
  [string? => map?]
  {:status 200 :body html :headers {"Content-Type" "text/html"}})

(defn csrf-token [req] (:io.pedestal.http.csrf/anti-forgery-token req))

(defn html-handler [req]
  (html-response (index (csrf-token req))))

;; If you don't have control of the client's request headers you can
;; force muuntaja to encode always.
;; Tell muuntaja to encode the response even if Accept header is not present.
;(def transit-interceptor
;  {:leave (fn [ctx] (assoc-in ctx [:response :muuntaja/encode] transit))})

;; If the response object contains a "Content-Type" header then muuntaja
;; will not encode the body.
;; If it is not present it will encoding using the default encoding if Accept header
;; is not present in the request or is */*
;; So we remove the header here so muuntaja will add it and encode the response body.
(defn api-handler [{:keys [body-params] :as req}]
  (when body-params
    (let [resp (handle-api-request
                 body-params
                 (fn [tx] (parser {:ring/request req} tx)))]
      (log/info "Outgoing response: " (pr-str resp))
      (update resp :headers dissoc "Content-Type"))))

(comment
  "Things available in (:request ctx)
  (
  :reitit.core/match :reitit.core/router :protocol :async-supported? :cookies
  :remote-addr :servlet-response :body-params :servlet :headers :server-port
  :servlet-request :muuntaja/request :content-length :session/key :content-type
  :path-info :uri :server-name :query-string :path-params :muuntaja/response
  :body :scheme :request-method :context-path :session
  )"
  )

(def log-req-keys
  [:path-info
   ;:body
   :uri
   :server-name
   ;:context-path
   :request-method
   :session])

(defn log-request-begin [req]
  (log/info "----------------------------------BEGIN-REQUEST--------------------------------------")
  (pprint
    (select-keys req log-req-keys))
  (flush))

(defn log-request-end []
  (log/info "------------------------------------END-REQUEST--------------------------------------"))

(def logger {:name  ::logger
             :enter (fn [ctx]
                      (let [req (:request ctx)
                            session (-> req :session)]
                        ;; add custom logging here
                        (log-request-begin req)
                        ctx))
             :leave (fn [ctx]
                      ;; add custom logging here.
                      (log-request-end)
                      ctx)})

(def transit-type "application/transit+json")

(defn make-muuntaja [dev?]
  (log/info "make-muuntaja dev? " dev?)
  (let [muu-config
        (-> muu/default-options
            (assoc :default-format transit-type)
            (update-in
              [:formats transit-type]
              merge
              {:decoder-opts {:verbose dev?}
               :encoder-opts {:verbose dev?}}))]
    (muu/create muu-config)))

(defn rrouter [dev?]
  (rhttp/router
    [["/" html-handler] ["/api" api-handler]]
    {:data {:muuntaja     (make-muuntaja dev?)
            :interceptors [(muuntaja/format-interceptor)
                           (parameters/parameters-interceptor)
                           (exception/exception-interceptor)
                           (multipart/multipart-interceptor)
                           ;; uncomment this if you want to force muuntaja to encode
                           ;transit-interceptor
                           ]}}))

(defn default-handler
  [req]
  (log/info "[default-handler]")
  (html-handler req))

(defn make-csp-policy
  [config]
  (let [{:keys [csp dev?]} (::config config)
        {:keys [font-domains style-domains]} csp]
    {:default-src "'self'"
     ;; Allow shadow cljs web sockets
     :connect-src "'self' *"
     :object-src  "none"
     :img-src     "'self' data:"
     :script-src  (str "'self' 'unsafe-inline'" (when dev? " 'unsafe-eval'"))
     :font-src    (string/join " " (list* "data:" font-domains))
     :style-src   (string/join " " (list* "'unsafe-inline'" style-domains))}))

(defn service [config]
  (let [{:keys [dev? disable-csrf?]} (::config config)]
    {:env                  :prod
     ::http/routes         []
     ::http/resource-path  "/public"
     ::http/enable-session {:store        (crux-session-store crux-session-node)
                            :cookie-attrs {:secure    (not dev?)
                                           :same-site :strict
                                           ;; expires in two weeks
                                           :max-age   1209600}}
     ::http/enable-csrf    (if disable-csrf? nil {})
     ::http/type           :jetty
     ::http/secure-headers {:content-security-policy-settings (make-csp-policy config)}
     ::http/port           8084}))

(defn router [{:keys [dev?]}]
  (rpedestal/routing-interceptor (rrouter dev?) default-handler))

(defn make-service-map
  ([] (make-service-map (service config)))
  ([base-map]
   (let [dev? (#{:dev} (:env base-map))]
     (-> base-map
         (cond->
           dev?
           (assoc ::http/allowed-origins {:creds true :allowed-origins (constantly true)}))

         http/default-interceptors
         ;; By default enabling csrf in pedestal also enables body parsing.
         ;; We remove it as muuntaja does content negotiation.
         (update ::http/interceptors
                 (fn [ints]
                   (vec (remove #(= (:name %) :io.pedestal.http.body-params/body-params) ints))))

         ;; use reitit for routing
         (rpedestal/replace-last-interceptor (router (::config config)))
         (update ::http/interceptors conj (interceptor/interceptor logger))
         (cond-> dev? http/dev-interceptors)))))

(comment (make-service-map (service config)))
