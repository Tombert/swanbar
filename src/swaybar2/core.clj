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

(defn- do-all-handler [i curr-state]
  (go
    (let 
      [kkey (-> i (get "name") keyword)
       ;curr-state @state
       ttl (-> i (get "ttl" 0) (Duration/ofMillis))
       nname (get i "name")
       is-async (get i "async" false)

       ; Don't love hard-coding this.  Might need to figure out
       ; a good way to avoid this. 
       async-timeout (-> i 
                         (get "async_timeout" 1000) 
                         Duration/ofMillis)
       now (-> (System/nanoTime) 
               Duration/ofNanos)
       is-processing (get-in curr-state [kkey :processing] false)
       expire-time (-> curr-state 
                       (get-in [kkey :expires] (Duration/ofNanos 0)))
       old-channel (get-in curr-state [kkey :channel])
       started (get-in curr-state [kkey :started] now)
       {:keys [ch-p n1]} (when (and (not is-processing) (pos? (.compareTo now expire-time)))
                   (let [ ch (if is-async (fetch-data kkey) (go (fetch-data kkey))) 
                         nstate (-> curr-state 
                                    (assoc-in [kkey :processing] true)
                                    (assoc-in [kkey :channel] ch)
                                    (assoc-in [kkey :expires] (.plus now ttl))
                                    (assoc-in [kkey :started] now))
                         ]
                     (swap! state
                            #(-> %
                                 (assoc-in [kkey :processing] true)
                                 (assoc-in [kkey :channel] ch)
                                 (assoc-in [kkey :expires] (.plus now ttl))
                                 (assoc-in [kkey :started] now)))
                     {:ch-p ch :n1 nstate}))
       ch (or ch-p old-channel)
       new-state (or n1 curr-state)
       old-data (get-in new-state [kkey :data])

       {:keys [poll-data n2]} (let [res (a/poll! ch) ]
                        (if res 
                          (let [
                                nstate (-> new-state 
                                           (assoc-in [kkey :processing] false)
                                           (assoc-in [kkey :data] res)) ]
                            (swap! state
                                   #(-> %
                                        (assoc-in [kkey :processing] false)
                                        (assoc-in [kkey :data] res)))           
                            {:poll-data res :n2 nstate}) 
                          (when (and is-async is-processing)
                            (let [delta (.minus now started)]
                              (when (> (.compareTo delta async-timeout) 0)
                                (let [
                                      nstate (-> new-state
                                                 (assoc-in [kkey :processing] false)
                                                 (assoc-in [kkey :expires] (Duration/ofNanos 0)))] 

                                  (swap! state
                                         #(-> %
                                              (assoc-in [kkey :processing] false)
                                              (assoc-in [kkey :expires] (Duration/ofNanos 0))))
                                {:poll-data nil :n2 nstate}))))))
       data (or poll-data old-data)
       new-state-2 (or n2 new-state)
       rendered (if data 
                  (render kkey (:data data)) 
                  {:out ""} )
       out-obj {:name nname
                :instance  nname
                :background (get i "background" "#000000")
                :color (get i "color" "#FFFFFF")
                :full_text (:out rendered)}
       ]
      {:module kkey :res out-obj :nstate new-state-2})))

(defn renderer [input-chan]
  (go-loop []
    (let [msg (<! input-chan)]
      (println msg))
    (recur)))

(defn do-all [my-timeout in-chan events module-map]
  (>!! in-chan "{\"version\":1, \"click_events\":true}")
  (>!! in-chan "[")
  (>!! in-chan "[],")
  (go-loop [my-state state]
           (let [
                 input (read-stdin-if-ready)
                 click-event (parse-std input)
                 chs (vec (for [i events]
                            (do
                              (do-all-handler i @my-state))))
                 results (loop [chs chs
                                acc [] ] 
                           (do 
                             (if (empty? chs)
                               (do (mapv :res acc))
                               (let [ch (first chs)
                                     res (<! ch)] 
                                 (recur 
                                   (rest chs) 
                                   (conj acc res)
                                   
                                   )))))
                 out-json (str 
                            (json/write-str results) 
                            ",")
                 ] 
             (mouse-handler click-event (get-in module-map [click-event "click_program"]))
             (>! in-chan out-json)
             (<! (timeout my-timeout))
             (recur my-state))))
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
          module-map (->> 
                       input 
                       (map 
                         (fn [i] 
                           [(keyword (get i "name")) i]))
                       (into {}))
          my-timeout (get-in in-obj ["poll_time"])

          in-chan (chan BUFFER-SIZE)
          ]
      (force-graal-to-include-processbuilder)
      (renderer in-chan)
      (do-all my-timeout in-chan input module-map)
      (<!! (chan)))))

