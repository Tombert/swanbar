(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'com.example/swaybar2)
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file "target/swaybar2.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/compile-clj {:basis basis
                  :src-dirs ["src" "resources"]
                  :class-dir class-dir})

  (b/copy-dir {:src-dirs ["resources"]
               :target-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file jar-file
           :basis basis
           :main 'swaybar2.core
           :compile-opts {:direct-linking true}
           :include-libs true})) ;; <--- THIS is key


