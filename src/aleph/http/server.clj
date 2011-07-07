;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns
  ^{:skip-wiki true}
  aleph.http.server
  (:use
    [aleph netty formats]
    [aleph.http utils core websocket]
    [aleph.http.server requests responses]
    [lamina core executors trace]
    [lamina.core.pipeline :only (success-result)]
    [clojure.pprint])
  (:require
    [clojure.contrib.logging :as log]
    [clojure.string :as str])
  (:import
    [org.jboss.netty.handler.codec.http
     DefaultHttpResponse
     HttpResponseStatus
     HttpVersion
     HttpRequest
     HttpHeaders
     HttpRequestDecoder
     HttpResponseEncoder
     HttpContentCompressor]
    [org.jboss.netty.channel
     Channel
     ChannelPipeline]
    [java.util.concurrent
     TimeoutException]))


;;;

(def continue-response
  (DefaultHttpResponse. HttpVersion/HTTP_1_1 HttpResponseStatus/CONTINUE))

(def error-response
  (transform-aleph-response {:status 500} nil))

(def timeout-response
  (transform-aleph-response {:status 408} nil))

(defn http-session-handler [handler options]
  (let [init? (atom false)
 	ch (channel)
	server-name (:name options)
	simple-handler (request-handler handler options)]
    (message-stage
      (fn [^Channel netty-channel request]
	(when (and
		(instance? HttpRequest request)
		(= "100-continue" (.getHeader ^HttpRequest request "Expect")))
	  (.write netty-channel continue-response))
	(if-not (or @init? (.isChunked ^HttpRequest request) (HttpHeaders/isKeepAlive request))
	  (run-pipeline (simple-handler netty-channel request)
	    :error-handler (fn [ex]
			     (let [response (if (or
						  (instance? InterruptedException ex)
						  (instance? TimeoutException ex))
					      timeout-response
					      error-response)]
			       (write-to-channel netty-channel response true)))
	    #(respond netty-channel options (first %) (second %))
	    (fn [_] (.close netty-channel)))
	  (do
	    (when (compare-and-set! init? false true)
	      (run-pipeline
		(receive-in-order (consume-request-stream netty-channel ch handler options)
		  #(respond netty-channel options (first %) (second %)))
		(fn [_] (.close netty-channel))))
	    (enqueue ch request)))
	nil))))

(defn create-pipeline
  "Creates an HTTP pipeline."
  [handler options]
  (let [netty-options (:netty options)
	pipeline ^ChannelPipeline
	(create-netty-pipeline (:name options)
	  :decoder (HttpRequestDecoder.
		     (get netty-options "http.maxInitialLineLength" 8192)
		     (get netty-options "http.maxHeaderSize" 16384)
		     (get netty-options "http.maxChunkSize" 16384))
	  :encoder (HttpResponseEncoder.)
	  :deflater (HttpContentCompressor.)
	  :http-request (http-session-handler handler options))]
    (when (:websocket options)
      (.addBefore pipeline "http-request" "websocket" (websocket-handshake-handler handler options)))
    pipeline))

(defn start-http-server
  "Starts an HTTP server on the specified :port.  To support WebSockets, set :websocket to
   true.

   'handler' should be a function that takes two parameters, a channel and a request hash.
   The request is a hash that conforms to the Ring standard, with :websocket set to true
   if it is a WebSocket handshake.  If the request is chunked, the :body will also be a
   channel.

   If the request is a standard HTTP request, the channel will accept a single message, which
   is the response.  For a chunked response, the response :body should be a channel.  If the
   request is a WebSocket handshake, the channel represents a full duplex socket, which
   communicates via complete (i.e. non-streaming) strings."
  [handler options]
  (let [options (merge options {:result-transform second})
	options (merge
		  {:name (str "http-server." (:port options))}
		  options
		  {:thread-pool (when (and
					(contains? options :thread-pool)
					(not (nil? (:thread-pool options))))
				  (thread-pool
				    (merge-with #(if (map? %1) (merge %1 %2) %2)
				      {:name (str "http-server." (:port options) ".thread-pool")}
				      (:thread-pool options))))})
	stop-fn (start-server
		  #(create-pipeline handler options)
		  options)]
    (fn []
      (async
	(try
	  (stop-fn)
	  (finally
	    (when-let [thread-pool (:thread-pool options)]
	      (shutdown-thread-pool thread-pool))))))))






