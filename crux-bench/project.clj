(defproject juxt/crux-bench "derived-from-git"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/data.json "0.2.7"]
                 [juxt/crux-core "derived-from-git"]
                 [juxt/crux-kafka "derived-from-git"]
                 [juxt/crux-kafka-embedded "derived-from-git"]
                 [juxt/crux-rocksdb "derived-from-git"]
                 [juxt/crux-test "derived-from-git"]
                 [ch.qos.logback/logback-classic "1.2.3"]

                 [clj-http "3.10.0" :exclusions [org.apache.httpcomponents/httpclient]]
                 [org.apache.httpcomponents/httpclient "4.5.9"]]

  :middleware [leiningen.project-version/middleware]

  :resource-paths ["resources" "data"]
  :jvm-opts ["-Xms3g" "-Xmx3g"]
  :uberjar-name "crux-bench-standalone.jar"
  :pedantic? :warn)
