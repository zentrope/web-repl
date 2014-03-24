(defproject com.zentrope/web-repl "0.1.0"
  :description "Embeddable JVM-hosted web-app enabling a JVM/Clojure repl in a browser."
  :url "https://github.com/zentrope/web-repl"
  :license {:name "EPL" :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0-RC3"]
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [compojure "1.1.6"]
                 [http-kit "2.1.18"]
                 [hiccup "1.0.5"]
                 [ring/ring-core "1.2.2"]]
  :cljsbuild {:builds []}
  :scm {:name "git" :url "https://github.com/zentrope/web-repl"}
  :main ^:skip-aot web-repl.core
  :target-path "target/%s"
  :min-lein-version "2.3.4"
  :jvm-opts ["-Dapple.awt.UIElement=true"]
  :jar-exclusions [#"logback.xml" #".DS_Store"]
  :aliases {"install!" ^{:doc "Push to local repo"}
               ["do" "clean"
                     ["cljsbuild" "clean"]
                     ["cljsbuild" "once"]
                     "install"]}
  :profiles {:dev {:plugins [[lein-cljsbuild "1.0.2"]]
                   :cljsbuild {:builds [{:id :dev
                                         :source-paths ["src-cljs"]
                                         :compiler {:output-to "resources/public/main.js"
                                                    :preamble ["react/react.min.js"]
                                                    :externs ["react/externs/react.js"]
                                                    :optimizations :whitespace
                                                    :pretty-print true}}]}
                   :hooks [leiningen.cljsbuild]
                   :dependencies [[ch.qos.logback/logback-classic "1.1.1"]
                                  [om "0.5.3"]
                                  [org.clojure/clojurescript "0.0-2173"
                                   :exclusions [org.clojure/tools.reader]]
                                  [sablono "0.2.14"
                                   :exclusions [org.clojure/tools.reader]]]}
             :uberjar {:aot :all}})
