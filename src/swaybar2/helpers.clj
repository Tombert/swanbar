(ns swaybar2.helpers
  (:gen-class)
  (:import [java.time LocalDateTime]
           [java.io File]
           [java.lang ProcessHandle ProcessBuilder ProcessBuilder$Redirect]
           [java.nio.channels Channels SelectableChannel Selector SelectionKey]
           [java.nio ByteBuffer]
           [java.nio.file Paths Files]
           [java.lang System]
           [java.util UUID]
           [java.time Duration])
  (:use [clojure.java.shell :only [sh]])
  (:require
   [hato.client :as hc]
   [clojure.java.io :as io]
   [clojure.data.json :as json]
   [clojure.core.async
    :as a
    :refer [>! <! >!! <!! go go-loop chan buffer close! thread
            alts! alts!! timeout]]))

(def open-ai-key (clojure.string/trim (slurp (str (System/getenv "HOME") "/.open-ai-key"))))

(defn get-filenames [directory]
   (try (let [^Path dir (Paths/get directory (make-array String 0))]
     (with-open [stream (Files/newDirectoryStream dir)]
       (->> stream (mapv str))))
        (catch Exception e
          (spit "/home/tombert/dbg" (str "Except:" e) :append true))))

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

(defn generate-wallpaper
  [prompt out-path ]
  (let [
        ret-chan (chan)
        api-key open-ai-key]
    (hc/post "https://api.openai.com/v1/images/generations"
             {
              :async? true
              :headers {"Authorization" (str "Bearer " api-key)
                        "Content-Type"  "application/json"}
              :as :json
              :body (json/write-str 
                      {:model "dall-e-3"
                       :prompt prompt
                       :n 1
                       :size "1792x1024"})}
             (fn [resp]
               (try 
                 (let [
                       bdy (json/read-str (get-in resp [:body]))
                       url (get-in bdy ["data" 0 "url"])
                       out-path (str out-path (str (UUID/randomUUID)) ".png")
                       ]
                   (with-open [in (io/input-stream (java.net.URL. url))
                               out (io/output-stream out-path)]
                     (io/copy in out)
                     (a/put! ret-chan out-path)))
                 (catch Exception e 
                   (spit "/home/tombert/dbg" (str "Error: " e) :append true)
                   ))))
    ret-chan))


(defn call-gpt [prompt role]
  (let [return-chan (chan)
        api-key open-ai-key
        body {:model "gpt-3.5-turbo"
              :messages [{:role "system"
                          :content role}
                         {:role "user"
                          :content prompt}]}]
    (hc/post "https://api.openai.com/v1/chat/completions"
             {:async? true
              :headers {"Authorization" (str "Bearer " api-key)
                        "Content-Type" "application/json"}
              :body (json/write-str body)
              :socket-timeout 3000
              :connect-timeout 3000}
             (fn [resp]
               (let [parsed (json/read-str
                             (:body resp)
                             :key-fn
                             keyword)
                     results (get-in
                              parsed
                              [:choices 0 :message :content])]
                 (a/put! return-chan results)))
             (fn [err]
               (a/put! return-chan :error)))
    return-chan))

(defn executable-dir []
  (let [handle (java.lang.ProcessHandle/current)
        info (.info handle)
        opt-cmd (.command info)
        cmd (.get opt-cmd)
        a (-> cmd
              (clojure.string/split #"/")
              pop)]
    (clojure.string/join "/" a)))

(defn run-detached [cmd & args]
  (try
    (let [pb (ProcessBuilder. (into [cmd] args))]
      (.redirectOutput pb ProcessBuilder$Redirect/DISCARD)
      (.redirectError pb ProcessBuilder$Redirect/DISCARD)
      (.start pb))
    (catch Exception e
      (spit "/home/tombert/dbg"
            (str "\nError: " (str e) "\n")
            :append true))))
