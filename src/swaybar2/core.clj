(ns swaybar2.core
  (:gen-class)
  (:import [java.time LocalDateTime])
  (:use [clojure.java.shell :only [sh]]
        ;[clojure.string :only split-lines]
        )
  (:require 
    [clojure.data.json :as json]
    [swaybar2.handlers :as h
     :refer [render]
     ]
    [clojure.core.async
             :as a
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout]])
  )

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

(defn update-wifi [my-timeout]
  (go-loop []
    (let [ 
          params (->> (sh "iw" "dev") :out (clojure.string/split-lines) (mapv clojure.string/trim))
          interface (-> params (get 5) (clojure.string/split #" ") last)
          ssid (-> params (get 9) (clojure.string/split #" ") last)
          iw-link (sh "iw" interface "link" )
          is-connected (not (nil? (clojure.string/index-of (sh "iw" interface "link") "Connected")))
          connect-status (if is-connected :connected :disconnected)]
      (swap! wifi-state assoc :ssid ssid :connect-status connect-status)
    (<! (timeout my-timeout))
    (recur))))

(defn update-battery [my-timeout]
  (go-loop []
           (let [
                 capacity (clojure.string/trim (clojure.string/replace (slurp "/sys/class/power_supply/BAT0/capacity") #"\"" ""))
                 ;_ (print capacity-raw)
                 ;capacity (Integer/parseInt capacity-raw)
                 status (->  "/sys/class/power_supply/BAT0/status" slurp (clojure.string/lower-case) (clojure.string/replace #" " "") clojure.string/trim keyword)
                 ]

             (swap! battery-state assoc :capacity capacity :status status)

             (<! (timeout my-timeout))
             (recur))))

(defn find-deep [x]
  (cond
    (and (map? x) (true? (get x "focused")))
    x

    (map? x)
    (some find-deep (vals x))

    (sequential? x)
    (some find-deep x)

    :else
    nil))


(defn update-selected-program [my-timeout]
  (go-loop []
           (let [
                 ;tree (-> (sh "swaymsg" "-t" "get_tree") :out (json/read-str))
                 selected-prog (-> (sh "swaymsg" "-t" "get_tree") :out (json/read-str) (find-deep) (get "app_id"))
                 ]
             
             (swap! prog-state assoc :current-prog selected-prog)
             (<! (timeout my-timeout))
             (recur))))




(defn update-date [my-timeout]
  (go-loop []
    (let [
          now (LocalDateTime/now)
          month (clojure.string/trim (str (.getMonth now)))
          day-of-week (str (.getDayOfWeek now))
          day-of-month (.getDayOfMonth now)
          year  (.getYear now)
          hour  (.getHour now)
          ssecond  (.getSecond now)
          minute  (.getMinute now)
          ] 
      (swap! 
        date-state 
        assoc 
        :month month 
        :day-of-week day-of-week 
        :day-of-month day-of-month 
        :year year 
        :hour hour 
        :second ssecond 
        :minute minute)
    (<! (timeout my-timeout))
    (recur))))


(defn renderer [my-timeout]

        
	(println "{\"version\":1, \"click_events\":true}")
	(println "[")
	(println "[],")

  (go-loop []
           (let [ddate-state @date-state
                 wwifi-state @wifi-state
                 pprog-state @prog-state
                 bbattery-state @battery-state
                 ]
             (when (not (or (empty? ddate-state) (empty? wwifi-state) (empty? bbattery-state)  ))
               (let [
                     rendered-date (->> ddate-state (render :date ) :out)
                     rendered-wifi (->> wwifi-state (render :wifi ) :out)
                     rendered-current-prog (->> pprog-state (render :selected ) :out)
                     rendered-battery (->> bbattery-state (render :battery ) :out)
                     out-obj [{:name "current" :instance "current" :full_text rendered-current-prog }
                              {:name "wifi" :instance "wifi" :full_text rendered-wifi }
                              {:name "battery" :instance "battery" :full_text rendered-battery }
                              {:name "time" :instance "time" :full_text rendered-date}]
                     out-json (json/write-str out-obj)
                     out-final (str out-json ",")
                     ]

                 (println out-final)))
             (<! (timeout my-timeout))
           (recur))))


; (defn wifi-click-handler [my-timeout]
;   (go-loop
;     (<! (timeout my-timeout))
;     (recur)))


(defn -main [] 
  (update-date DATE-TIMEOUT)
  (update-battery BATTERY-TIMEOUT)
  (update-selected-program PROG-TIMEOUT)
  (update-wifi WIFI-TIMEOUT)
  (renderer 100)


   (<!! (chan)))

