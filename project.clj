(defproject missionary-lab "0.1.0-SNAPSHOT"
  :description "Experiments with Missionary"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[clj-http/clj-http "3.13.1"]
                 [hato/hato "1.0.0"]
                 [java-http-clj/java-http-clj "0.4.3"]
                 [missionary/missionary "b.45"]
                 [org.babashka/http-client "0.4.22"]
                 [org.clojure/clojure "1.11.1"]
                 [org.clojure/tools.logging "1.3.0"]
                 [org.slf4j/slf4j-simple "2.0.17"]]
  :main ^:skip-aot missionary-lab.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
