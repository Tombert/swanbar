(ns swaybar2.core
  (:gen-class)
  (:import [java.time LocalDateTime]
           [java.nio.channels Channels SelectableChannel Selector SelectionKey]
           [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.nio.file Files Paths StandardOpenOption]
           [java.util.concurrent Executors]
           [java.nio ByteBuffer]
           [java.lang System]
           [java.time Duration])
  (:use [clojure.java.shell :only [sh]])
  (:require
   [clojure.core.async.impl.dispatch :as dispatch]
   [clojure.data.json :as json]
   [msgpack.core :as msg]
   [msgpack.clojure-extensions]
   [swaybar2.handlers :as h
    :refer [render fetch-data cleanup process mouse-handler]]
   [clojure.core.async
    :as a
    :refer [>! <! >!! <!! go go-loop chan buffer close! thread
            alts! alts!! timeout]]))

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

(defn- parse-std [input]
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

(defn- poller [ch state kkey ^Duration started ^Duration now is-async is-processing async-timeout]
  (let [res (a/poll! ch)]
    (if res
      (let [
            p-data (->> res :data (process kkey))
            nstate (-> state
                       (assoc-in [kkey :processing] false)
                       (assoc-in [kkey :data] res)
                       (assoc-in [kkey :p-data] p-data))]
        {:poll-data res :n2 nstate})
      (when (and is-async is-processing)
        (let [delta (.minus now started)]
          (when (pos? (.compareTo delta async-timeout))
            (let [nstate (-> state
                             (assoc-in [kkey :processing] false)
                             (assoc-in [kkey :expires] 0))]
              {:poll-data nil :n2 nstate})))))))

(defn- maybe-start-tasks [curr-state misc kkey is-async is-processing ^Duration now ^Duration expire-time ^Duration ttl]
  (when (and (not is-processing) (pos? (.compareTo now expire-time)))
    (let [ch (if is-async (fetch-data kkey misc) (go (fetch-data kkey misc)))
          _ (cleanup kkey (get-in curr-state [kkey :p-data :data]))
          ;p-data (process kkey (get-in curr-state [kkey :data :data]))
          nstate (-> curr-state
                     (assoc-in [kkey :processing] true)
                     (assoc-in [kkey :channel] ch)
                     (assoc-in [kkey :expires] (.toMillis (.plus now ttl)))
                     (assoc-in [kkey :started] (.toMillis now))
                     
                     )]
      {:ch-p ch :n1 nstate})))

(defn- do-all-handler [i curr-state]
  (go
    (let
     [
      ;_ (println (str "\n\n\n\n curr-state" curr-state))
      kkey (-> i (get "name") keyword)
      ttl (-> i
              (get "ttl" 0)
              Duration/ofMillis)
      nname (get i "name")
      orig-out (get-in curr-state [kkey :out]  {:out "Waiting..."})
      is-async (get i "async" false)

       ; Don't love hard-coding this.  Might need to figure out
       ; a good way to avoid this. 
      async-timeout (-> i
                        (get "async_timeout" 1000)
                        Duration/ofMillis)
      misc (-> i (get "misc"))
      now (-> (System/currentTimeMillis)
              Duration/ofMillis)
      is-processing (get-in curr-state [kkey :processing] false)
      expire-time (-> curr-state
                      (get-in [kkey :expires] 0)
                      Duration/ofMillis)

      old-channel (get-in curr-state [kkey :channel] (chan))
      started (-> curr-state  
                  (get-in [kkey :started] (.toMillis now)) 
                  Duration/ofMillis)
      {:keys [ch-p n1]} (maybe-start-tasks curr-state misc kkey is-async is-processing now expire-time ttl)
      ch (or ch-p old-channel)
      new-state (or n1 curr-state)
      old-data (get-in new-state [kkey :data])

       ; a ton of arguments, probably need to consolidate some of this stuff, 
       ; but this function was getting gigantic so I wanted to start splitting 
       ; stuff up. 
      {:keys [poll-data n2]} (poller ch new-state kkey started now is-async is-processing async-timeout)
      data (or poll-data old-data)
      new-state-2 (or n2 new-state)
      rendered (if data
                 (render kkey (:data data))
                 orig-out)
      new-state-3 (assoc-in new-state-2 [kkey :out] rendered)
      out-obj {:name nname
               :instance  nname
               :background (get i "background" "#000000")
               :color (get i "color" "#FFFFFF")
               :full_text (:out rendered)}]
      {:module kkey :res out-obj :nstate new-state-3})))

(defn renderer [input-chan]
  (go-loop []
    (let [msg (<! input-chan)]
      (println msg))
    (recur)))

(defn do-all [^Duration my-timeout in-chan events module-map init-state persist-chan]
  (>!! in-chan "{\"version\":1, \"click_events\":true}")
  (>!! in-chan "[")
  (>!! in-chan "[],")
  (go-loop [my-state init-state]
           (let [
                 start (-> (System/nanoTime) Duration/ofNanos)
                 input (read-stdin-if-ready)
                 click-event (parse-std input)
                 chs (vec (for [i events]
                            (do
                              (do-all-handler i my-state))))
                 results-p (loop [chs chs
                                  acc []]
                             (do
                               (if (empty? chs)
                                 (do acc)
                                 (let [ch (first chs)
                                       res (<! ch)]
                                   (recur
                                     (rest chs)
                                     (conj acc res))))))

                 ;results (mapv :res results-p)
                 n-state (->> results-p
                              (reduce
                                (fn [interim i]
                                  (let [kkey (get-in i [:module])
                                        value (get-in i [:nstate kkey])]
                                    (assoc interim kkey value))) {}))
                 results (->> results-p
                              (mapcat
                                (fn [i]
                                  (if (-> module-map 
                                          (get-in 
                                            [(:module i) "display"] 
                                            true))
                                    [(:res i)]
                                    []))))
                 out-json (str
                            (json/write-str results)
                            ",")
                 ]
             (mouse-handler click-event (get-in module-map [click-event "click_program"]))
             (>! in-chan out-json)
             (let [
                   time-taken (-> (System/nanoTime) (Duration/ofNanos ) (.minus start)) 
                   delta (.minus my-timeout time-taken)
                   final-timeout (max 0 (.toMillis delta))]

               (>! persist-chan n-state)
               (<! (timeout final-timeout))
               (recur n-state)))))
(defn- args-to-keys [args]
  (->> args
       (map clojure.string/trim)
       (map keyword)
       vec))

(defn write-bytes [^String path ^bytes data]
  (let [p (Paths/get path (make-array String 0))]
    (Files/write p data (into-array StandardOpenOption
                                    [StandardOpenOption/CREATE
                                     StandardOpenOption/WRITE
                                     StandardOpenOption/TRUNCATE_EXISTING]))))

(defn persist [in-ch out-path buffer-size]
    (go-loop [counter 0]
             (let [ res (<! in-ch)
                   new-res (reduce-kv (fn [m k v]
                                (assoc m k (-> v (dissoc :p-data) (dissoc :channel))))
                              {}
                              res)
                   packed-msg (msg/pack new-res)
                   ]
               (when (= counter 0)
                 (write-bytes out-path packed-msg))
               (recur (mod (inc counter) buffer-size)))))

(defn read-bytes [^String path]
  (try 
    (Files/readAllBytes (Paths/get path (make-array String 0))) 
  (catch Exception e 
    nil)))

(defn -main [& args]
  (System/setProperty "org.apache.commons.logging.Log" "org.apache.commons.logging.impl.NoOpLog")
  (let [executor-var (ns-resolve 'clojure.core.async.impl.dispatch 'EXECUTOR)]
    (when executor-var
      (alter-var-root executor-var
                      (constantly (Executors/newFixedThreadPool 1))))
    (let [config-json-path (if (empty? args)  "swaybar-config.json" (first args))
          config-json (slurp config-json-path)
          persist-chan (chan 10)

          config-obj (json/read-str config-json)
          input (get-in config-obj ["modules"])
          state-path (get-in config-obj ["persist" "path"])
          persist-buffer-size (get-in config-obj ["persist" "buffer_size"])
          init-state-bytes (read-bytes state-path)
          init-state (if init-state-bytes (msg/unpack init-state-bytes) {})
          ;_ (println (str "Init state: " init-state))
          module-map (->>
                      input
                      (map
                       (fn [i]
                         [(keyword (get i "name")) i]))
                      (into {}))
          my-timeout (-> config-obj 
                         (get-in ["poll_time"]) 
                         Duration/ofMillis)

          in-chan (chan BUFFER-SIZE)]
      (force-graal-to-include-processbuilder)
      (persist persist-chan state-path persist-buffer-size)
      (renderer in-chan)
      (do-all my-timeout in-chan input module-map init-state persist-chan)
      (<!! (chan)))))

