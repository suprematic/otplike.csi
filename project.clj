(defproject otplike/otplike.csi "0.4.0-SNAPSHOT"
  :description "CSI backend for otplike"
  :url "https://github.com/suprematic/otplike.csi"
  :license {:name "Eclipse Public License - v1.0"
            :url  "https://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.match "1.0.0"]
                 [otplike "0.6.0-alpha"]
                 [http-kit "2.3.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [com.cognitect/transit-clj "0.8.313"]])
