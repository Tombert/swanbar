(ns swaybar2.handlers
  (:gen-class)
  (:import [java.time LocalDateTime]
           [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.nio.channels Channels SelectableChannel Selector SelectionKey]
           [java.nio ByteBuffer]
           [java.lang System]
           [java.time Duration]
           )
  (:use [clojure.java.shell :only [sh]]
        ;[clojure.string :only split-lines]
        )
  (:require 
    [hato.client :as hc]
    [clojure.data.json :as json]
    ;[clj-http.client :as http]
    [clojure.core.async
             :as a
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout]]))


(def open-ai-key (clojure.string/trim (slurp (str (System/getenv "HOME") "/.open-ai-key"))))

(def wifi-map {:connected  "📶"
               :disconnected "❌"
               })

(def battery-map {:charging "⚡"
                  :discharging "🔋"
                  :notcharging "🔌"
                  :full "🟢"
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
(defn generate-quote [topic]
  (let [api-key open-ai-key
        body {:model "gpt-3.5-turbo"
              :messages [{:role "system"
                          :content "You are a quote generator."}
                         {:role "user"
                          :content (str "Give me a unique medium-sized inspirational quote involving " topic " with an attribution to a fictional author whose name is a pun on " topic)}]}
        resp (hc/post "https://api.openai.com/v1/chat/completions"
                      {:headers {"Authorization" (str "Bearer " api-key)
                                 "Content-Type" "application/json"}
                       :body (json/write-str body)
                       :socket-timeout 3000
                       :connect-timeout 3000})
        parsed (json/read-str (:body resp) :key-fn keyword)]
    (get-in parsed [:choices 0 :message :content])))

(defn generate-mock [shells]
  (let [api-key open-ai-key
        body {:model "gpt-3.5-turbo"
              :messages [{:role "system"
                          :content "You are an insult generator."}
                         {:role "user"
                          :content (str "Roast and make fun of this shell history with a short quip: " shells)}]}
        resp (hc/post "https://api.openai.com/v1/chat/completions"
                      {:headers {"Authorization" (str "Bearer " api-key)
                                 "Content-Type" "application/json"}
                       :body (json/write-str body)
                       :socket-timeout 3000
                       :connect-timeout 3000})
        parsed (json/read-str (:body resp) :key-fn keyword)]
    (get-in parsed [:choices 0 :message :content])))
; (defn generate-quote [topic]
;   (let [api-key open-ai-key
;         body {:model "gpt-3.5-turbo"
;               :messages [{:role "system"
;                           :content "You are a quote generator."}
;                          {:role "user"
;                           :content (str "Give me a unique inspirational quote involving " topic)}]}
;         response (http/post "https://api.openai.com/v1/chat/completions"
;                             {:headers {"Authorization" (str "Bearer " api-key)
;                                        "Content-Type" "application/json"}
;                              :body (json/write-str body)
;                              :socket-timeout 3000
;                              :conn-timeout 3000})
;         parsed (json/read-str (:body response) :key-fn keyword)
;         haiku (get-in parsed [:choices 0 :message :content])]
;     haiku))

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
   (let [
         is-muted (:is-muted volume)
         small-speaker-cutoff 40
         mid-speaker-cutoff 80
         vol-raw (:volume volume)
         vol (-> vol-raw (clojure.string/replace #"%" "") (Integer/parseInt))
         symb (cond 
                is-muted "🔇"
                (< vol small-speaker-cutoff)  "🔈"
                (< vol mid-speaker-cutoff)  "🔉"
                :else "🔊"
                )]
     {:out (str symb vol "%")}))


(defmethod render :wifi [_ wifi] 
  (let [
        symb (->> wifi :connect-status (get wifi-map))
        ssid (->> wifi :ssid )
        ]
   ;{:out (str ssid " " symb )}
   {:out (str symb )}
   
   ))

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
  {:out "NOT AVAILABLE"})

(defmulti fetch-data 
  (fn [method _]
    method))
(def quote-topics ["dogs" "cheese" "oranges" "sperm" "pineapples" "pressure cookers" "diet soda" "yoga" "milkshake" "fried chicken" "belly buttons" "napkins" "yarn" "heathcliff the cat" "ginger ale" "shampoo" "vacuum cleaners" "laptops" "books" "them" "nothing" "robots" "iPads" "socks" "laughter" "pizza" "rabbits" "wasps" "bookshelves" "flags" "blankets" "probiotics" "vitamins"])

(defmethod fetch-data :shellmock [_ timeout]
  (let [
       now (System/currentTimeMillis)
       expires-at (+ timeout now)
       shell-lines (->> (str (System/getenv "HOME") "/.zsh_history")
                        slurp 
                        clojure.string/split-lines 
                        (take-last 15) 
                        (clojure.string/join "\n"))
       mock (generate-mock shell-lines)
       ]

    {:expires expires-at
     :data {
            :mock mock 
            }}))

(defmethod fetch-data :quote [_ timeout]
  (let [
       now (System/currentTimeMillis)
       expires-at (+ timeout now)
       qquote (generate-quote (get quote-topics (rand-int (count quote-topics))))
       ]

    {:expires expires-at
     :data {
            :quote qquote
            }}))

(defmethod fetch-data :volume [_ timeout] 
  (let [
        is-muted (-> 
                   (sh "pactl" "get-sink-mute" "@DEFAULT_SINK@") 
                   :out 
                   clojure.string/trim 
                   (clojure.string/split #" ") 
                   last 
                   (= "yes"))
        vol-info (-> 
                   (sh "pactl" "get-sink-volume" "@DEFAULT_SINK@") 
                   :out 
                   (clojure.string/split #" ") ) 
        volume-level (if (= "/" (get vol-info 5)) (get vol-info 4) (get vol-info 5))
        now (System/currentTimeMillis)
        ]
     {:expires (+ now timeout)
      :data {:is-muted is-muted 
             :volume volume-level}}))

(defmethod fetch-data :date [_ timeout]
  (let [
          now (LocalDateTime/now)
          month (clojure.string/trim (str (.getMonth now)))
          day-of-week (str (.getDayOfWeek now))
          day-of-month (.getDayOfMonth now)
          year  (.getYear now)
          hour  (.getHour now)
          ssecond  (.getSecond now)
          minute  (.getMinute now)
          now (System/currentTimeMillis)
          expire-time (+ now timeout)
          ] 
    {:expires expire-time
     :data {
        :month month 
        :day-of-week day-of-week 
        :day-of-month day-of-month 
        :year year 
        :hour hour 
        :second ssecond 
        :minute minute
        } }))

(defmethod fetch-data :wifi [_ timeout]
  (let [ 
        params (->> (sh "iw" "dev") :out (clojure.string/split-lines) (mapv clojure.string/trim))
        interface (-> params (get 5) (clojure.string/split #" ") last)
        ssid (-> params (get 9) (clojure.string/split #" ") last)
        iw-link (sh "iw" interface "link" )
        is-connected (not (nil? (clojure.string/index-of (sh "iw" interface "link") "Connected")))
        connect-status (if is-connected :connected :disconnected)
        now (System/currentTimeMillis)
        expire-time (+ now timeout)
        ]
    {:expires expire-time
     :data {
            :ssid ssid 
            :connect-status connect-status }}))

(defmethod fetch-data :selected [_ timeout]
  (let [selected-prog (-> 
                        (sh "swaymsg" "-t" "get_tree") 
                        :out 
                        (json/read-str) 
                        (find-deep) 
                        (get "app_id"))
        now (System/currentTimeMillis)
        expire-time (+ now timeout)
        ]
    {:expires expire-time
     :data 
     {
      :current-prog selected-prog}}))

(defmethod fetch-data :battery [_ timeout]
  (let [
        bat-path "/sys/class/power_supply/BAT0"
        capacity (clojure.string/trim 
                   (clojure.string/replace 
                     (slurp (str bat-path "/capacity")) #"\"" ""))
        status (->  
                 (str bat-path "/status") 
                 slurp 
                 (clojure.string/lower-case) 
                 (clojure.string/replace #" " "") 
                 clojure.string/trim keyword)
        now (System/currentTimeMillis)
        expire-time (+ now timeout)
        ]
    {:expires expire-time 
     :data 
     { 
      :capacity capacity :status status}}))


(defmethod fetch-data :default [_ timeout]
  {:data {}}
  )

(defn- run-detached [cmd & args]
  (let [pb (ProcessBuilder. (into [cmd] args))]
    (.redirectOutput pb ProcessBuilder$Redirect/DISCARD)
    (.redirectError pb ProcessBuilder$Redirect/DISCARD)
    (.start pb))) 

(defmulti mouse-handler (fn [a] a))

(defmethod mouse-handler :wifi [_]
  (run-detached "iwgtk"))

(defmethod mouse-handler :volume [_]
  (run-detached "pavucontrol"))

(defmethod mouse-handler :default [_]
  
  )

