(defproject jsk "0.1.0-SNAPSHOT"
  :description "job scheduling"
  :url "http://jsk.io"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-1878"]
                 [org.clojure/core.typed "0.2.3"]    ; static type checking
                 [clojurewerkz/quartzite "1.1.0"]    ; clojure wrapper around quartz scheduling
                 [org.zeroturnaround/zt-exec "1.4"]  ; process execution
                 [com.taoensso/timbre "2.6.1"]       ; logging
                 [com.postspectacular/rotor "0.1.0"] ; rotating log file appender
                 [lib-noir "0.6.8"]
                 [enlive "1.1.4"]                    ; templating library
                 [compojure "1.1.5"]                 ; routing library for ring
                 [ring-server "0.2.8"]
                 [com.h2database/h2 "1.3.173"]       ; embedded db
                 [korma "0.3.0-RC5"]                 ; sql dsl
                 [fogus/ring-edn "0.2.0"]
                 [ring-middleware-format "0.3.1"]
                 [enfocus "2.0.0-SNAPSHOT"]]         ; cljs enlive like lib
  :source-paths ["src/clj"]
  :profiles {:production {:ring {:open-browser? false :stacktraces? false :auto-reload? false}}
             :dev {:ring {:port 8080 :nrepl {:start? true :port 8081}}
                   :dependencies [[midje "1.5.1"]
                                  [ring-mock "0.1.5"]
                                  [ring/ring-devel "1.1.8"]]}}
  :plugins [[lein-ring "0.8.7"]
            [lein-typed "0.3.0"]
            [lein-cljsbuild "0.3.3"]]
  :ring {:handler jsk.handler/war-handler
         :init jsk.handler/init
         :destroy jsk.handler/destroy}
  :cljsbuild {:builds [{:source-paths ["src/cljs"]
                        :compiler {:output-to "resources/public/js/main.js"
                                   :optimizations :whitespace
                                   :pretty-print true}}]}
  ; :core.typed {:check [jsk.core jsk.handler jsk.ps jsk.repl jsk.quartz]}
  :min-lein-version "2.0.0")
