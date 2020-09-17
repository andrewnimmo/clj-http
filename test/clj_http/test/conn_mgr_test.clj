(ns clj-http.test.conn-mgr-test
  (:require [clj-http.conn-mgr :as conn-mgr]
            [clj-http.core :as core]
            [clj-http.test.core-test :refer [run-server]]
            [clojure.test :refer :all]
            [ring.adapter.jetty :as ring])
  (:import java.security.KeyStore
           [javax.net.ssl KeyManagerFactory TrustManagerFactory]
           org.apache.hc.client5.http.HttpRoute
           org.apache.hc.client5.http.socket.PlainConnectionSocketFactory
           org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory
           org.apache.hc.core5.http.HttpHost))

(def client-ks "test-resources/client-keystore")
(def client-ks-pass "keykey")
(def secure-request {:request-method :get :uri "/"
                     :server-port 18084 :scheme :https
                     :keystore client-ks :keystore-pass client-ks-pass
                     :trust-store client-ks :trust-store-pass client-ks-pass
                     :server-name "localhost" :insecure? true})

(defn secure-handler [req]
  (if (nil? (:ssl-client-cert req))
    {:status 403}
    {:status 200}))

(deftest load-keystore
  (let [ks (conn-mgr/get-keystore "test-resources/keystore" nil "keykey")]
    (is (instance? KeyStore ks))
    (is (> (.size ks) 0))))

(deftest use-existing-keystore
  (let [ks (conn-mgr/get-keystore "test-resources/keystore" nil "keykey")
        ks (conn-mgr/get-keystore ks)]
    (is (instance? KeyStore ks))
    (is (> (.size ks) 0))))

(deftest load-keystore-with-nil-pass
  (let [ks (conn-mgr/get-keystore "test-resources/keystore" nil nil)]
    (is (instance? KeyStore ks))))

(deftest keystore-scheme-factory
  (let [sr (conn-mgr/get-keystore-scheme-registry
            {:keystore client-ks :keystore-pass client-ks-pass
             :trust-store client-ks :trust-store-pass client-ks-pass})
        plain-socket-factory (.lookup sr "http")
        ssl-socket-factory (.lookup sr "https")]
    (is (instance? PlainConnectionSocketFactory plain-socket-factory))
    (is (instance? SSLConnectionSocketFactory ssl-socket-factory))))

#_
(deftest keystore-session-strategy
  (let [strategy-registry (conn-mgr/get-keystore-strategy-registry
                           {:keystore client-ks
                            :keystore-pass client-ks-pass
                            :trust-store client-ks
                            :trust-store-pass client-ks-pass})
        noop-session-strategy (.lookup strategy-registry "http")
        ssl-session-strategy (.lookup strategy-registry "https")]
    (is (instance? NoopIOSessionStrategy noop-session-strategy))
    (is (instance? SSLIOSessionStrategy ssl-session-strategy))))

(def array-of-trust-manager
  (let [ks (conn-mgr/get-keystore "test-resources/keystore" nil "keykey")
        tmf (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
              (.init ks))]
    (.getTrustManagers tmf)))

(def array-of-key-manager
  (let [ks (conn-mgr/get-keystore "test-resources/keystore" nil "keykey")
        tmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
              (.init ks (.toCharArray "keykey")))]
    (.getKeyManagers tmf)))

(deftest managers-scheme-factory
  (doseq [[trust-managers key-managers] [[array-of-trust-manager array-of-key-manager]
                                         [(seq array-of-trust-manager) (seq array-of-key-manager)]
                                         [(first (seq array-of-trust-manager)) (first (seq array-of-key-manager))]]]
    (let [scheme-registry (conn-mgr/get-managers-scheme-registry
                           {:trust-managers trust-managers
                            :key-managers key-managers})
          plain-socket-factory (.lookup scheme-registry "http")
          ssl-socket-factory (.lookup scheme-registry "https")]
      (is (instance? PlainConnectionSocketFactory plain-socket-factory))
      (is (instance? SSLConnectionSocketFactory ssl-socket-factory)))))


(deftest ^:integration ssl-client-cert-get
  (let [server (ring/run-jetty secure-handler
                               {:port 18083 :ssl-port 18084
                                :ssl? true
                                :join? false
                                :keystore "test-resources/keystore"
                                :key-password "keykey"
                                :client-auth :want})]
    (try
      (let [resp (core/request {:request-method :get :uri "/get"
                                :server-port 18084 :scheme :https
                                :insecure? true :server-name "localhost"})]
        (is (= 403 (:status resp))))
      (let [resp (core/request secure-request)]
        (is (= 200 (:status resp))))
      (finally
        (.stop server)))))

(deftest ^:integration ssl-client-cert-get-async
  (let [server (ring/run-jetty secure-handler
                               {:port 18083 :ssl-port 18084
                                :ssl? true
                                :join? false
                                :keystore "test-resources/keystore"
                                :key-password "keykey"
                                :client-auth :want})]
    (try
      (let [resp (promise)
            exception (promise)
            _ (core/request {:request-method :get :uri "/get"
                             :server-port 18084 :scheme :https
                             :insecure? true :server-name "localhost"
                             :async? true} resp exception)]
        (is (= 403 (:status (deref resp 1000 {:status :timeout})))))
      (let [resp (promise)
            exception (promise)
            _ (core/request (assoc secure-request :async? true) resp exception)]
        (is (= 200 (:status (deref resp 1000 {:status :timeout})))))
      (finally
        (.stop server)))))


(deftest ^:integration t-closed-conn-mgr-for-as-stream
  (run-server)
  ;; timeouts forces an exception being thrown
  (let [cm (conn-mgr/make-regular-conn-manager {:socket/timeout 1})]
    (with-redefs-fn {#'conn-mgr/make-regular-conn-manager (constantly cm)}
      #(try
         (core/request {:request-method :get :uri "/timeout"
                        :server-port 18080 :scheme :http
                        :server-name "localhost"
                        :as :stream})
         (is false "request should have thrown an exception")
         (catch Exception e)))

    (is (thrown-with-msg? IllegalStateException #"Connection pool shut down"
                          (.lease cm "12345" (HttpRoute. (HttpHost/create "https://localhost:18080"))  {})))))

(deftest ^:integration t-closed-conn-mgr-for-empty-body
  (run-server)
  (let [cm (conn-mgr/make-regular-conn-manager {})]
    (with-redefs-fn {#'conn-mgr/make-regular-conn-manager (constantly cm)}
      #(core/request {:request-method :get :uri "/unmodified-resource"
                      :server-port 18080 :scheme :http
                      :server-name "localhost"}))
    (is (thrown-with-msg? IllegalStateException #"Connection pool shut down"
                          (.lease cm "12345" (HttpRoute. (HttpHost/create "https://localhost:18080"))  {})))))

;; TODO: remove me, this is a poor way of defining reusability
#_(deftest t-reusable-conn-mgrs
  (let [regular (conn-mgr/make-regular-conn-manager {})
        regular-reusable (conn-mgr/make-reusable-conn-manager {})
        async (conn-mgr/make-regular-async-conn-manager {})
        async-reusable (conn-mgr/make-reusable-async-conn-manager {})
        async-reuseable (conn-mgr/make-reuseable-async-conn-manager {})]
    (is (false? (conn-mgr/reusable? regular)))
    (is (true? (conn-mgr/reusable? regular-reusable)))
    (is (true? (conn-mgr/reusable? async)))
    (is (true? (conn-mgr/reusable? async-reusable)))
    (is (true? (conn-mgr/reusable? async-reuseable)))))
