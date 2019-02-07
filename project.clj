(defproject otplike/otplike.csi "0.2.1"
  :description "CSI backend for otplike"
  :url "https://github.com/suprematic/otplike.csi"
  :license {:name "Eclipse Public License - v1.0"
            :url  "https://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/core.match "0.3.0-alpha5"]
                 [otplike "0.5.0-alpha"]
                 [http-kit "2.3.0"]
                 [com.taoensso/timbre "4.10.0"]
                 [com.cognitect/transit-clj "0.8.313"]]

  :source-paths  ["src"])
