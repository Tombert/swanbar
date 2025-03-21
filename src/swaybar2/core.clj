(ns swaybar2.core
  (:gen-class)
  (:import [java.time LocalDateTime]
           [java.nio.channels Channels SelectableChannel Selector SelectionKey]
           [java.lang ProcessBuilder ProcessBuilder$Redirect]

           [java.util.concurrent Executors]
           [java.nio ByteBuffer]
           [java.lang System]
           [java.time Duration]
           )
  (:use [clojure.java.shell :only [sh]])
  (:require 
    [clojure.core.async.impl.dispatch :as dispatch]
    [clojure.data.json :as json]
    [swaybar2.handlers :as h
     :refer [render fetch-data mouse-handler]
     ]
    [clojure.core.async
             :as a
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout]])) 

(def state (atom {}))

;(def TIMEOUT-MS 50)

(def BUFFER-SIZE 50)

(defn force-graal-to-include-processbuilder []
  (doto (ProcessBuilder. ["true"])
    (.redirectOutput ProcessBuilder$Redirect/INHERIT)
    (.redirectError ProcessBuilder$Redirect/INHERIT)))

(defn read-stdin-if-ready []
  (let [in System/in]
    (when (pos? (.available in))
      (let [buffer (byte-array (.available in))]
        (.read in buffer)
        (String. buffer)))))

(defn- parse-n-key [input]
  (-> input
      json/read-str 
      (get "instance") 
      (clojure.string/trim) 
      keyword))

(defn parse-std [input]
  (if (and 
        (not (nil? input)) 
        (not (empty? (clojure.string/trim input))))
    (cond
      (= (first input) \,) (-> input 
                               (subs 1) 
                               parse-n-key)
      (= (first input) \{) (-> input 
                               parse-n-key)
      :else :nothing)))

(defn- do-all-handler [i]
  (go
     (let [kkey (-> i (get "name") keyword)
           curr-state @state
           ttl (-> i (get "ttl" 0))
           nname (get i "name")
           is-async (get i "async" false)
           async-timeout (get i "async_timeout" 0)
           now (System/currentTimeMillis)
           is-processing (get-in curr-state [kkey :processing] false)
           expire-time (get-in curr-state [kkey :expires] 0)
           old-channel (get-in curr-state [kkey :channel])
           ch (if (and (not is-processing) (> now expire-time))
                      (let [
                            ch (if is-async (fetch-data kkey) (go (fetch-data kkey)))
                            ]
                        (swap! state assoc-in [kkey :processing] true)
                        (swap! state assoc-in [kkey :channel] ch)
                        (swap! state assoc-in [kkey :expires] (+ ttl now))
                        ;(get-in curr-state [kkey :data])       
                        ch
                        ) old-channel)
           old-data (get-in curr-state [kkey :data])

           poll-data (when (let [res (a/poll! ch) ]
                       (if res 
                         (do
                           (swap! state assoc-in [kkey :processing] false)
                           (swap! state assoc-in [kkey :data] res)
                           res) 
                         nil)))
           data (or poll-data old-data)

          rendered (if data (render kkey (:data data)) {:out ""} )
          out-obj {:name nname
                   :instance  nname
                   :background (get i "background" "#000000")
                   :color (get i "color" "#FFFFFF")
                   :full_text (:out rendered)}
          ]
       out-obj)))

(defn renderer [input-chan]
  (go-loop []
    (let [msg (<! input-chan)]
      (println msg))
    (recur)))

(defn do-all [my-timeout in-chan events]
  (>!! in-chan "{\"version\":1, \"click_events\":true}")
  (>!! in-chan "[")
  (>!! in-chan "[],")
  (go-loop []
           (let [
                 input (read-stdin-if-ready)
                 click-event (parse-std input)
                 chs (vec (for [i events]
                            (do-all-handler i)))
                 results (loop [chs chs
                                acc []] 
                           (do 
                             (if (empty? chs)
                               (do acc)
                               (let [ch (first chs)
                                     res (<! ch)] 
                                 (recur 
                                   (rest chs) 
                                   (conj acc res))))))
                 out-json (str 
                            (json/write-str results) 
                            ",")] 
             (mouse-handler click-event)
             (>! in-chan out-json)
             (<! (timeout my-timeout))
             (recur))))
(defn- args-to-keys [args]
  (->> args
       (map clojure.string/trim)
       (map keyword)
       vec
       ))

(defn -main [& args]
  (System/setProperty "org.apache.commons.logging.Log" "org.apache.commons.logging.impl.NoOpLog")
  (let [executor-var (ns-resolve 'clojure.core.async.impl.dispatch 'EXECUTOR)]
    (when executor-var
      (alter-var-root executor-var
                      (constantly (Executors/newFixedThreadPool 1))))
    (let [
          json-path (if (empty? args)  "swaybar-config.json" (first args))
          input-json (slurp json-path)

          in-obj (json/read-str input-json)
          input (get-in in-obj ["modules"])
          my-timeout (get-in in-obj ["poll_time"])

          in-chan (chan BUFFER-SIZE)
          ]
      (force-graal-to-include-processbuilder)
      (renderer in-chan)
      (do-all my-timeout in-chan input)
      (<!! (chan)))))

