(ns crux.fixtures.kafka
  (:require [clojure.java.io :as io]
            [crux.fixtures.api :as apif]
            [crux.fixtures.kv-only :refer [*kv-module*]]
            [crux.io :as cio]
            [crux.kafka :as k]
            [crux.kafka.consumer :as kc]
            [crux.kafka.embedded :as ek]
            [crux.api :as api]
            [crux.fixtures :as f])
  (:import [java.util Properties UUID]
           crux.api.ICruxAsyncIngestAPI
           org.apache.kafka.clients.admin.AdminClient
           org.apache.kafka.clients.consumer.KafkaConsumer
           org.apache.kafka.clients.producer.KafkaProducer))

(def ^:dynamic *kafka-bootstrap-servers*)
(def ^:dynamic ^String *tx-topic*)
(def ^:dynamic ^String *doc-topic*)

(defn write-kafka-meta-properties [log-dir broker-id]
  (let [meta-properties (io/file log-dir "meta.properties")]
    (when-not (.exists meta-properties)
      (io/make-parents meta-properties)
      (with-open [out (io/output-stream meta-properties)]
        (doto (Properties.)
          (.setProperty "version" "0")
          (.setProperty "broker.id" (str broker-id))
          (.store out ""))))))

(def ^:dynamic ^AdminClient *admin-client*)

(defn with-embedded-kafka-cluster [f]
  (f/with-tmp-dir "zk" [zk-data-dir]
    (f/with-tmp-dir "kafka-log" [kafka-log-dir]
      (write-kafka-meta-properties kafka-log-dir ek/*broker-id*)

      (let [zookeeper-port (cio/free-port)
            kafka-port (cio/free-port)]

        (with-open [embedded-kafka (ek/start-embedded-kafka
                                    #:crux.kafka.embedded{:zookeeper-data-dir (str zk-data-dir)
                                                          :zookeeper-port zookeeper-port
                                                          :kafka-log-dir (str kafka-log-dir)
                                                          :kafka-port kafka-port})
                    admin-client (k/create-admin-client
                                  {"bootstrap.servers" (get-in embedded-kafka [:options :bootstrap-servers])})]

          (binding [*admin-client* admin-client
                    *kafka-bootstrap-servers* (get-in embedded-kafka [:options :bootstrap-servers])]
            (f)))))))

(def ^:dynamic ^KafkaProducer *producer*)
(def ^:dynamic ^KafkaConsumer *consumer*)
(def ^:dynamic ^KafkaConsumer *consumer2*)

(def ^:dynamic *consumer-options* {})

(defn with-consumer []
  (kc/create-consumer
   (merge {"bootstrap.servers" *kafka-bootstrap-servers*
           "group.id" (str (UUID/randomUUID))}
          *consumer-options*)))

(defn with-kafka-client [f & {:keys [consumer-options]}]
  (with-open [producer (k/create-producer {"bootstrap.servers" *kafka-bootstrap-servers*})
              consumer (kc/create-consumer
                        (merge {"bootstrap.servers" *kafka-bootstrap-servers*
                                "group.id" (str (UUID/randomUUID))}
                               *consumer-options*))
              consumer2 (kc/create-consumer
                         (merge {"bootstrap.servers" *kafka-bootstrap-servers*
                                 "group.id" (str (UUID/randomUUID))}
                                *consumer-options*))]
    (binding [*producer* producer
              *consumer* consumer
              *consumer2* consumer2]
      (f))))

(def ^:dynamic *cluster-node*)

(defn with-cluster-node-opts [f]
  (assert (bound? #'*kafka-bootstrap-servers*))
  (let [test-id (UUID/randomUUID)]
    (binding [*tx-topic* (str "tx-topic-" test-id)
              *doc-topic* (str "doc-topic-" test-id)]
      (apif/with-opts {:crux.node/topology ['crux.kafka/topology]
                       :crux.node/kv-store *kv-module*
                       :crux.kafka/tx-topic *tx-topic*
                       :crux.kafka/doc-topic *doc-topic*
                       :crux.kafka/bootstrap-servers *kafka-bootstrap-servers*} f))))

(def ^:dynamic ^ICruxAsyncIngestAPI *ingest-client*)

(defn with-ingest-client [f]
  (assert (bound? #'*kafka-bootstrap-servers*))
  (let [test-id (UUID/randomUUID)]
    (with-open [ingest-client (api/new-ingest-client {:crux.kafka/tx-topic *tx-topic*
                                                      :crux.kafka/doc-topic *doc-topic*
                                                      :crux.kafka/bootstrap-servers *kafka-bootstrap-servers*})]
      (binding [*ingest-client* ingest-client]
        (f)))))
