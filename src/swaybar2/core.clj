(ns swaybar2.core
  (:gen-class)
  (:import [java.time LocalDateTime]
           [java.nio.channels Channels SelectableChannel Selector SelectionKey]
           [java.lang ProcessBuilder ProcessBuilder$Redirect]

           [java.nio ByteBuffer]
           [java.lang System]
           )
  (:use [clojure.java.shell :only [sh]]
        ;[clojure.string :only split-lines]
        )
  (:require 
    [clojure.data.json :as json]
    [swaybar2.handlers :as h
     :refer [render fetch-data mouse-handler]
     ]
    [clojure.core.async
             :as a
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout]])
  )
;
; (def stdin-channel
;   (-> System/in
;       Channels/newChannel))

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

(def date-state (atom {}))
(def wifi-state (atom {}))
(def prog-state (atom {}))
(def battery-state (atom {}))

(def DATE-TIMEOUT 500)
(def WIFI-TIMEOUT 100)
(def PROG-TIMEOUT 100)
(def BATTERY-TIMEOUT 100)

; (slurp "/sys/class/power_supply/BAT0/capacity")
; (def interface (last (split (get (mapv trim (split-lines (:out (sh "iw" "dev"))) ) 5) #" ")))

; (index-of (sh "iw" interface "link") "Connected")

(def wifi-map {:connected  "📶"
               :disconnected "❌"
               })
(def battery-map {:charging "⚡"
                  :discharging "🔋"
                  :notcharging "🔌"
                  })

(defn parse-std [input]
  (if (and (not (nil? input)) (not (empty? (clojure.string/trim input))))
    (cond
      (= (first input) \,) (-> input (subs 1) (json/read-str) (get "instance") (clojure.string/trim) keyword )
      (= (first input) \{) (-> input (json/read-str) (get "instance") (clojure.string/trim) keyword)
      :else :nothing)))

(defn- do-all-handler [i]
     (let [data (fetch-data i)
          rendered (render i (:data data))
          input (read-stdin-if-ready)
          click-event (parse-std input)
          ; _ (spit "/home/tombert/dbg" "poop" :append true)
          out-obj {:name i :instance i :full_text (:out rendered)}
          ]
      (mouse-handler click-event)
      out-obj)
         )

(defn do-all [my-timeout events]
  (println "{\"version\":1, \"click_events\":true}")
  (println "[")
  (println "[],")
  (go-loop []
           (let [
                 chs (vec (for [i events]
                            (go (do-all-handler i))))
                 results (loop [chs chs
                                acc []
                                ] (do 
                                    (if (empty? chs)
                                      (do acc)
                                      (let [ch (first chs)
                                            res (<! ch)
                                            ] 
                                        (recur (rest chs) (conj acc res))))))
                 out-json (str (json/write-str results) ",")] 
             (println out-json)
             (<! (timeout my-timeout))
             (recur))))


(defn -main [] 

  (force-graal-to-include-processbuilder)
  ; (update-date DATE-TIMEOUT)
  ; (update-battery BATTERY-TIMEOUT)
  ; (update-selected-program PROG-TIMEOUT)
  ; (update-wifi WIFI-TIMEOUT)
  ; (renderer 100)
  (do-all 100 [:selected :wifi :battery :date])


   (<!! (chan)))

