(ns swaybar2.handlers
  (:gen-class)
  (:import [java.time LocalDateTime]
           [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.nio.channels Channels SelectableChannel Selector SelectionKey]
           [java.nio ByteBuffer]
           [java.lang System]
           )
  (:use [clojure.java.shell :only [sh]]
        ;[clojure.string :only split-lines]
        )
  (:require 
    [clojure.data.json :as json]
    [clojure.core.async
             :as a
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout]]))

(def wifi-map {:connected  "📶"
               :disconnected "❌"
               })

(def battery-map {:charging "⚡"
                  :discharging "🔋"
                  :notcharging "🔌"
                  })

(def day-abbrev {
                  "MONDAY" "Mon"
                  "TUESDAY" "Tues"
                  "WEDNESDAY" "Wed"
                  "THURSDAY" "Thurs"
                  "FRIDAY" "Fri"
                  "SATURDAY" "Sat"
                  "SUNDAY" "Sun"
                  })

(def month-abbrev {
                   "JANUARY" "Jan"
                   "FEBRUARY" "Feb"
                   "MARCH" "Mar"
                   "APRIL" "Apr"
                   "MAY" "May"
                   "JUNE" "Jun"
                   "JULY" "Jul"
                   "AUGUST" "Aug"
                   "SEPTEMBER" "Sep"
                   "OCTOBER" "Oct"
                   "NOVEMBER" "Nov"
                   "DECEMBER" "Dec"
                   })


(defn- find-deep [x]
  (cond
    (and (map? x) (true? (get x "focused")))
    x

    (map? x)
    (some find-deep (vals x))

    (sequential? x)
    (some find-deep x)

    :else
    nil))


(defmulti render 
  (fn [method _]
    method))

(defmethod render :wifi [_ wifi] 
  (let [
        symb (->> wifi :connect-status (get wifi-map))
        ssid (->> wifi :ssid )
        ]
   {:out (str ssid " " symb )}))

(defmethod render :battery [_ battery]
  (let [capacity (:capacity battery)
        status (:status battery) 
        symb (get battery-map status "❓")
        ]
    {:out (str symb " " capacity "%")}))

(defmethod render :date [_ date]
  (let [weekday (get day-abbrev (:day-of-week date) (:day-of-week date))
        month (get month-abbrev (:month date) (:month date))
        day (:day-of-month date)
        hour (format "%02d" (mod (:hour date) 12))
        minute (format "%02d" (:minute date))
        ampm (if ( < (:hour date) 12) "AM" "PM")
        ]
 {:out (str weekday " " 
            month  " " 
            day " " 
            hour ":" minute " " 
            ampm)}))


(defmethod render :selected [_ selected]
  (let [ curr (:current-prog selected)]
    {:out (str curr)}))

(defmethod render :default [_ _]
  {:out "NOT AVAILABLE" }
  )


(defmulti fetch-data 
  (fn [method]
    method))

(defmethod fetch-data :date [_]
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
    {:data {
        :month month 
        :day-of-week day-of-week 
        :day-of-month day-of-month 
        :year year 
        :hour hour 
        :second ssecond 
        :minute minute
        } }))

(defmethod fetch-data :wifi [_]
  (let [ 
        params (->> (sh "iw" "dev") :out (clojure.string/split-lines) (mapv clojure.string/trim))
        interface (-> params (get 5) (clojure.string/split #" ") last)
        ssid (-> params (get 9) (clojure.string/split #" ") last)
        iw-link (sh "iw" interface "link" )
        is-connected (not (nil? (clojure.string/index-of (sh "iw" interface "link") "Connected")))
        connect-status (if is-connected :connected :disconnected)]
    {:data {
            :ssid ssid 
            :connect-status connect-status }}))


(defmethod fetch-data :selected [_]
  (let [selected-prog (-> 
                        (sh "swaymsg" "-t" "get_tree") 
                        :out 
                        (json/read-str) 
                        (find-deep) 
                        (get "app_id"))]
    {:data 
     {
      :current-prog selected-prog}}))

(defmethod fetch-data :battery [_]
  (let [
        bat-path "/sys/class/power_supply/BAT0"
        capacity (clojure.string/trim 
                   (clojure.string/replace 
                     (slurp (str bat-path "/capacity")) #"\"" ""))
        status (->  (str bat-path "/status") slurp (clojure.string/lower-case) (clojure.string/replace #" " "") clojure.string/trim keyword) ]
    {:data 
     { 
      :capacity capacity :status status}}))

(defn- run-detached [cmd & args]
  (let [pb (ProcessBuilder. (into [cmd] args))]
    (.redirectOutput pb ProcessBuilder$Redirect/DISCARD)
    (.redirectError pb ProcessBuilder$Redirect/DISCARD)
    (.start pb))) 

(defmulti mouse-handler (fn [a] a))

(defmethod mouse-handler :wifi [_]
  (spit "/home/tombert/dbg" "howdy" :append true)
  (run-detached "iwgtk"))

(defmethod mouse-handler :default [_]
  
  )

