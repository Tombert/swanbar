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

(def TIMEOUT-MS 50)

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
     (let [kkey (-> i (get "name") keyword)
           curr-state @state
           timeout (-> i (get "timeout"))
           now (System/currentTimeMillis)
           data (if (> now (or (get-in curr-state [kkey :expires]) 0))
                  (let [results (fetch-data kkey timeout)]
                      (swap! state assoc kkey results)
                      results)
                  (get-in curr-state [kkey]))
          rendered (render kkey (:data data))
          ; _ (spit "/home/tombert/dbg" "poop" :append true)
          out-obj {:name (get i "name") :instance (get i "name") :full_text (:out rendered)}
          ]
       out-obj))

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
                            (go (do-all-handler i))))
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
             ;(println out-json)
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
  (let [executor-var (ns-resolve 'clojure.core.async.impl.dispatch 'EXECUTOR)]
    (when executor-var
      (alter-var-root executor-var
                      (constantly (Executors/newFixedThreadPool 1))))
    (let [
          json-path (if (empty? args)  "swaybar-config.json" (first args))
          input-json (slurp json-path)
          input (json/read-str input-json)
          in-chan (chan 20)
          ]
      (force-graal-to-include-processbuilder)
      (renderer in-chan)
      (do-all TIMEOUT-MS in-chan input)
      (<!! (chan)))))

