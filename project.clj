(defproject missionary-lab "0.1.0-SNAPSHOT"
  :description "Experiments with Missionary"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[hato/hato "1.0.0"]
                 [missionary/missionary "b.45"]
                 [org.clojure/clojure "1.11.1"]
                 [org.clojure/tools.logging "1.3.0"]
                 [org.slf4j/slf4j-simple "2.0.17"]]
  :main ^:skip-aot missionary-lab.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
