
(ns swaybar2.handlers
  (:gen-class)
  (:import [java.time LocalDateTime]
           [java.io File]
           [java.lang ProcessHandle ProcessBuilder ProcessBuilder$Redirect]
           [java.nio.channels Channels SelectableChannel Selector SelectionKey]
           [java.nio ByteBuffer]
           [java.lang System]
           [java.time Duration])
  (:use [clojure.java.shell :only [sh]])
  (:require
   [hato.client :as hc]
   [clojure.data.json :as json]
   [swaybar2.helpers
    :refer [executable-dir run-detached call-gpt generate-mock get-filenames generate-wallpaper]]
   [clojure.core.async
    :as a
    :refer [>! <! >!! <!! go go-loop chan buffer close! thread
            alts! alts!! timeout]]))

(def wifi-map {:connected  "📶"
               :disconnected "❌"})

(def battery-map {:charging "⚡"
                  :discharging "🔋"
                  :notcharging "🔌"
                  :full "🟢"})

(def day-abbrev
  {"MONDAY" "Mon"
   "TUESDAY" "Tues"
   "WEDNESDAY" "Wed"
   "THURSDAY" "Thurs"
   "FRIDAY" "Fri"
   "SATURDAY" "Sat"
   "SUNDAY" "Sun"})

(def month-abbrev
  {"JANUARY" "Jan"
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
   "DECEMBER" "Dec"})

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

(defmethod render :quote [_ qquote]
  {:out (str (:quote qquote))})

(defmethod render :shellmock [_ mock]
  {:out (str (:mock mock))})

(defmethod render :volume [_ volume]
  (let [is-muted (:is-muted volume)
        small-speaker-cutoff 40
        mid-speaker-cutoff 80
        vol-raw (:volume volume)
        vol (-> vol-raw (clojure.string/replace #"%" "") (Integer/parseInt))
        symb (cond
               is-muted "🔇"
               (< vol small-speaker-cutoff)  "🔈"
               (< vol mid-speaker-cutoff)  "🔉"
               :else "🔊")]
    {:out (str symb vol "%")}))

(defmethod render :wifi [_ wifi]
  (let [symb (->> wifi :connect-status (get wifi-map))
        ssid (->> wifi :ssid)]
   ;{:out (str ssid " " symb )}
    {:out (str symb)}))

(defmethod render :battery [_ battery]
  (let [capacity (:capacity battery)
        status (:status battery)
        symb (get battery-map status "❓")]
    {:out (str symb " " capacity "%")}))

(defmethod render :date [_ date]
  (let [weekday (get day-abbrev (:day-of-week date) (:day-of-week date))
        month (get month-abbrev (:month date) (:month date))
        day (:day-of-month date)
        hour (format "%02d" (mod (:hour date) 12))
        minute (format "%02d" (:minute date))
        ampm (if (< (:hour date) 12) "AM" "PM")]
    {:out (str weekday " "
               month  " "
               day " "
               hour ":" minute " "
               ampm)}))

(defmethod render :selected [_ selected]
  (let [curr (:current-prog selected)]
    {:out (str curr)}))

(defmethod render :default [_ _]
  {:out "NOT AVAILABLE"})

(defn- quote-topics- [_x]
  (let [lines (-> (str (executable-dir) "/topics")
                  slurp
                  clojure.string/split-lines)]
    lines))

(def quote-topics (memoize quote-topics-))


(defmulti process 
  (fn [method _]
    method))

(defmethod process :bg-changer [_ data]
  (let [background (:background data)
        pid (run-detached "swaybg" "-i" background "-m" "stretch")]
    {:data {:pid pid}}))

(defmethod process :default [_ _])


(defmulti fetch-data
  (fn [method _]
    method))

; (def backgrounds ["/home/tombert/wallpapers/awesome.png" 
;                   "/home/tombert/wallpapers/background.png"])

; (defmethod fetch-data :bg-fetcher [_ misc]
;   (go 
;     (spit "/home/tombert/dbg" "\n\n\n\n\n Made it to fetchdata" :append true)
;     {:data {:nada ""}}))
(defmethod fetch-data :bg-fetcher [_ misc]
  (go
    (spit "/home/tombert/dbg" "\n\n\n\n\n Made it to fetchdata" :append true)
    (let [directory (-> misc (get-in ["directory"]))
          rint1 (->> (quote-topics "")
                     count
                     rand-int)
          rint2 (->> (quote-topics "")
                     count
                     rand-int)
          topic1 (get (quote-topics "") rint1)
          topic2 (get (quote-topics "") rint2)
          prompt (str "Create a desktop wallpaper involving " topic1 " and " topic2 " interacting with each other.")
          ]
      (<! (generate-wallpaper prompt directory))
      {:data {:nada ""}})))

(defmethod fetch-data :bg-changer [_ misc]
  (let [backgrounds (-> misc (get-in ["directory"]) get-filenames)
         rint (rand-int (count backgrounds))
         background (get backgrounds rint)]
  {:data {
          :background background
           }}))

(defmethod fetch-data :shellmock [_ _]
  (let [shell-lines (->> (str (System/getenv "HOME") "/.zsh_history")
                         slurp
                         clojure.string/split-lines
                         (take-last 15)
                         (clojure.string/join "\n"))
        mock (generate-mock shell-lines)]

    {:data {:mock mock}}))

(defmethod fetch-data :quote [_ _]
  (go
    (let [rint (->> (quote-topics "")
                    count
                    rand-int)
          topic (get (quote-topics "") rint)
          prompt (str "Give me a unique medium-sized inspirational quote involving " topic " with an attribution to a fictional author whose name is a pun on " topic)
          role "You are a quote generator"
          quote-chan (call-gpt prompt role)
          qquote (<! quote-chan)]
      {:data {:quote qquote}})))

(defmethod fetch-data :volume [_ _]
  (let [is-muted (-> (sh "pactl" "get-sink-mute" "@DEFAULT_SINK@")
                     :out
                     clojure.string/trim
                     (clojure.string/split #" ")
                     last
                     (= "yes"))
        vol-info (-> (sh "pactl" "get-sink-volume" "@DEFAULT_SINK@")
                     :out
                     (clojure.string/split #" "))
        volume-level (if (= "/" (get vol-info 5))
                       (get vol-info 4)
                       (get vol-info 5))]
    {:data {:is-muted is-muted
            :volume volume-level}}))

(defmethod fetch-data :date [_ _]
  (let [now (LocalDateTime/now)
        month (clojure.string/trim (str (.getMonth now)))
        day-of-week (str (.getDayOfWeek now))
        day-of-month (.getDayOfMonth now)
        year  (.getYear now)
        hour  (.getHour now)
        ssecond  (.getSecond now)
        minute  (.getMinute now)
        now (System/currentTimeMillis)]
    {:data {:month month
            :day-of-week day-of-week
            :day-of-month day-of-month
            :year year
            :hour hour
            :second ssecond
            :minute minute}}))

(defmethod fetch-data :wifi [_ _]
  (let [params (->> (sh "iw" "dev")
                    :out
                    clojure.string/split-lines
                    (mapv clojure.string/trim))
        interface (-> params
                      (get 5)
                      (clojure.string/split #" ")
                      last)
        ssid (-> params
                 (get 9)
                 (clojure.string/split #" ")
                 last)
        iw-link (sh "iw" interface "link")
        is-connected (not
                      (nil?
                       (clojure.string/index-of
                        (sh "iw" interface "link")
                        "Connected")))
        connect-status (if is-connected :connected :disconnected)]
    {:data {:ssid ssid
            :connect-status connect-status}}))

(defmethod fetch-data :selected [_ _]
  (let [selected-prog (->
                       (sh "swaymsg" "-t" "get_tree")
                       :out
                       (json/read-str)
                       (find-deep)
                       (get "app_id"))]
    {:data
     {:current-prog selected-prog}}))

(defmethod fetch-data :battery [_ _]
  (let [bat-path "/sys/class/power_supply/BAT0"
        capacity (clojure.string/trim
                  (clojure.string/replace
                   (slurp (str bat-path "/capacity"))
                   #"\""
                   ""))
        status (-> (str bat-path "/status")
                   slurp
                   (clojure.string/lower-case)
                   (clojure.string/replace #" " "")
                   clojure.string/trim keyword)]
    {:data {:capacity capacity
            :status status}}))

(defmethod fetch-data :default [_ _]
  {:data {}})

(defmulti mouse-handler (fn [a program] a))

(defmethod mouse-handler :wifi [_ program]
  (run-detached program))

(defmethod mouse-handler :volume [_ program]
  (run-detached program))

(defmethod mouse-handler :default [_ program])

(defmulti cleanup (fn [a _] a))

(defmethod cleanup :bg-changer [_ bg-data]
  (let [^Process pid (get-in bg-data [:pid])]
    (when pid (.destroy pid))))

(defmethod cleanup :default [_ _])
