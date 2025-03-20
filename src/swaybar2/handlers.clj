(ns swaybar2.handlers
  (:gen-class)
  (:import [java.time LocalDateTime])
  (:use [clojure.java.shell :only [sh]]
        ;[clojure.string :only split-lines]
        )
  (:require 
    [clojure.data.json :as json]
    [clojure.core.async
             :as a
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout]])
  )

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

(defmulti render (fn [method _]
  method
  ))

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
 {:out (str (get day-abbrev (:day-of-week date) (:day-of-week date)) " " (get month-abbrev (:month date) (:month date))  " " (:day-of-month date) " " (format "%02d" (mod (:hour date) 12)) ":" (format "%02d" (:minute date)) " " (if ( < (:hour date) 12) "AM" "PM" ))})


(defmethod render :selected [_ selected]
  (let [ curr (:current-prog selected)]
    {:out (str curr)}))



