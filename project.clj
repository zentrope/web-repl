(defproject web-repl "0.1.0"
  :description "Experimental app running a repl in the browser to a JVM process."
  :url "https://github.com/zentrope"
  :license {:name "EPL" :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[ch.qos.logback/logback-classic "1.1.1"]
                 [compojure "1.1.6"]
                 [http-kit "2.1.18"]
                 [hiccup "1.0.5"]
                 [om "0.5.3"]
                 [org.clojure/clojure "1.6.0-RC1"]
                 [org.clojure/clojurescript "0.0-2173"
                  :exclusions [org.clojure/tools.reader]]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [ring/ring-core "1.2.2"]
                 [sablono "0.2.14"
                  :exclusions [org.clojure/tools.reader]]]
  :plugins [[lein-cljsbuild "1.0.2"]]
  :cljsbuild {:builds [{:source-paths ["src-cljs"]
                        :compiler {:output-to "resources/public/main.js"
                                   :preamble ["react/react.min.js"]
                                   :externs ["react/externs/react.js"]
                                   :optimizations :whitespace
                                   :pretty-print true}}]}
  :hooks [leiningen.cljsbuild]
  :main ^:skip-aot web-repl.core
  :target-path "target/%s"
  :min-lein-version "2.3.4"
  :jvm-opts ["-Dapple.awt.UIElement=true"]
  :profiles {:uberjar {:aot :all}})
