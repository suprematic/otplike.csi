(def core-async-version "0.3.443")

(defproject otplike/otplike.csi "0.1.0-SNAPSHOT"
  :description "Erlang/OTP like processes and behaviours on top of core.async"
  :license {:name "Eclipse Public License - v1.0"
            :url  "https://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.match "0.3.0-alpha4"]
                 [otplike "0.4.0-alpha"]
                 [http-kit "2.2.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [com.cognitect/transit-clj "0.8.309"]]

  :source-paths  ["src"]

  :plugins [[lein-ancient "0.6.10"]])
