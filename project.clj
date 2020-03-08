(defproject re-frisk-rn "0.1.1"
  :description "Simple debugging tool for React Native re-frame apps. **shadow-cljs compatible**."
  :url "https://github.com/flexsurfer/re-frisk-rn"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [ring/ring-core "1.8.0"]
                 [ring-cors "0.1.8"]
                 [http-kit "2.3.0"]
                 [re-frisk/sente "1.15.0"]
                 [compojure "1.6.1"]
                 [com.cognitect/transit-clj  "0.8.319"]
                 [javax.servlet/servlet-api "2.5"]
                 [org.clojure/core.async "0.7.559"]

                 [re-frisk "0.5.4"]
                 [reagent "0.8.0"]
                 [re-frame "0.10.1"]])

  ;:main re-frisk-rn.core)
