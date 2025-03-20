(ns swaybar2.core
  (:gen-class)
  (:import [java.time LocalDateTime]
           [java.nio.channels Channels SelectableChannel Selector SelectionKey]
           [java.lang ProcessBuilder ProcessBuilder$Redirect]

           [java.util.concurrent Executors]
           [java.nio ByteBuffer]
           [java.lang System]
           )
  (:use [clojure.java.shell :only [sh]]
        ;[clojure.string :only split-lines]
        )
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

(def TIMEOUT-MS 50)

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
     (let [data (fetch-data i)
          rendered (render i (:data data))
          ; _ (spit "/home/tombert/dbg" "poop" :append true)
          out-obj {:name i :instance i :full_text (:out rendered)}
          ]
      out-obj)
         )

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
                                acc []
                                ] 
                           (do 
                             (if (empty? chs)
                               (do acc)
                               (let [ch (first chs)
                                     res (<! ch) ] 
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


(defn -main []
  (let [executor-var (ns-resolve 'clojure.core.async.impl.dispatch 'EXECUTOR)]
    (when executor-var
      (alter-var-root executor-var
                      (constantly (Executors/newFixedThreadPool 1)))))
  ;; rest of your main...
  (let [in-chan (chan 20)]
    (force-graal-to-include-processbuilder)
    (renderer in-chan)
    (do-all 50 in-chan [:volume :selected :wifi :battery :date])
    (<!! (chan))))

