# web-repl

An embeddable JVM-hosted web-app enabling a JVM/Clojure repl in a
browser.

The idea is that as part of your start up of your back-end application
(Clojure on the JVM), you also start up a Web REPL service. You then
hit the web page for the port you configured and you can access the
app via a REPL in a browser.

Why?

So you can demo stuff and inspect stuff, say, in a review meeting of
some sort.

## Usage

Add the following as a dependency to your project:

    [com.zentrope/web-repl "0.1.0"]

Then, in your actual project, you can start and stop the `web-repl`
service however you need to in your application.

For example

```clojure
(ns trepl.core
  (:require
    [web-repl.core :as wrepl])
  (:gen-class))

(defn- on-jvm-shutdown!
  [f]
  (doto (Runtime/getRuntime)
    (.addShutdownHook (Thread. f))))

(defn- hello
  [msg]
  (println (str "Hello, " msg "!")))

(defn -main
  [& args]
  (println "Hello, World!")
  (let [state (wrepl/make-wrepl 2012)
        lock (promise)]
    (on-jvm-shutdown! (fn [] (wrepl/stop! state)))
    (on-jvm-shutdown! (fn [] (deliver lock :done)))
    (wrepl/start! state)
    (deref lock))))
```

Surf to `http://localhost:2012` and, in the appropriate "reader" area,
type:

```clojure
(ns trepl.core)
(hello "lovely lady")
```

and then hit `Shift-Enter`.

## License

Copyright &copy; 2014 Keith Irwin

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
