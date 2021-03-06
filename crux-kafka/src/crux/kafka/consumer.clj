(ns crux.kafka.consumer
  (:require [clojure.tools.logging :as log]
            [crux.db :as db]
            [crux.status :as status])
  (:import crux.kafka.nippy.NippyDeserializer
           java.io.Closeable
           java.time.Duration
           [java.util List Map]
           [org.apache.kafka.clients.consumer ConsumerRebalanceListener ConsumerRecord KafkaConsumer]
           org.apache.kafka.common.TopicPartition))

(def default-consumer-config
  {"enable.auto.commit" "false"
   "isolation.level" "read_committed"
   "auto.offset.reset" "earliest"
   "key.deserializer" (.getName NippyDeserializer)
   "value.deserializer" (.getName NippyDeserializer)})

(defn create-consumer
  ^org.apache.kafka.clients.consumer.KafkaConsumer [config]
  (KafkaConsumer. ^Map (merge default-consumer-config config)))

(defprotocol Offsets
  (read-offsets [this])
  (store-offsets [this offsets]))

(defrecord TxOffset [indexer]
  Offsets
  (read-offsets [this]
    {:next-offset (inc (get (db/read-index-meta indexer :crux.tx/latest-completed-tx) :crux.tx/tx-id 0))})

  ;; no-op - this is stored by the indexer itself
  (store-offsets [this offsets]))

(defrecord ConsumerOffsets [indexer k]
  Offsets
  (read-offsets [this]
    (db/read-index-meta indexer k))
  (store-offsets [this offsets]
    (db/store-index-meta indexer k offsets)))

(defn- topic-partition-meta-key [^TopicPartition partition]
  (keyword "crux.kafka.topic-partition" (str partition)))

(defn seek-to-stored-offsets [offsets ^KafkaConsumer consumer partitions]
  (let [consumer-state (read-offsets offsets)]
    (doseq [^TopicPartition partition partitions]
      (if-let [offset (get-in consumer-state [(topic-partition-meta-key partition) :next-offset])]
        (.seek consumer partition (long offset))
        (.seekToBeginning consumer [partition])))))

(defn update-stored-consumer-state [offsets ^KafkaConsumer consumer records]
  (let [partition->records (group-by (fn [^ConsumerRecord r]
                                       (TopicPartition. (.topic r)
                                                        (.partition r))) records)
        partitions (vec (keys partition->records))
        stored-consumer-state (or (read-offsets offsets) {})
        consumer-state (->> (for [^TopicPartition partition partitions
                                  :let [^ConsumerRecord last-record-in-batch (->> (get partition->records partition)
                                                                                  (sort-by #(.offset ^ConsumerRecord %))
                                                                                  (last))
                                        next-offset (inc (.offset last-record-in-batch))]]
                              [(topic-partition-meta-key partition)
                               {:next-offset next-offset}])
                            (into stored-consumer-state))]
    (store-offsets offsets consumer-state)))

(defn- prune-consumer-state [offsets ^KafkaConsumer consumer partitions]
  (let [consumer-state (read-offsets offsets)]
    (->> (for [^TopicPartition partition partitions
               :let [partition-key (topic-partition-meta-key partition)
                     next-offset (or (get-in consumer-state [partition-key :next-offset]) 0)]]
           [partition-key {:next-offset next-offset}])
         (into {})
         (store-offsets offsets))))

;; TODO: This works as long as each node has a unique consumer group
;; id, if not the node will only get a subset of the doc-topic. The
;; tx-topic is always only one partition.
(defn subscribe-from-stored-offsets
  [offsets ^KafkaConsumer consumer ^List topics]
  (.subscribe consumer
              topics
              (reify ConsumerRebalanceListener
                (onPartitionsRevoked [_ partitions]
                  (log/info "Partitions revoked:" (str partitions)))
                (onPartitionsAssigned [_ partitions]
                  (log/info "Partitions assigned:" (str partitions))
                  (prune-consumer-state offsets consumer partitions)
                  (seek-to-stored-offsets offsets consumer partitions)))))

(defrecord IndexingConsumer [running? ^Thread worker-thread consumer-config]
  status/Status
  (status-map [_]
    {:crux.zk/zk-active?
     (try
       (with-open [^KafkaConsumer consumer (create-consumer (merge consumer-config {"default.api.timeout.ms" (int 1000)}))]
         (boolean (.listTopics consumer)))
       (catch Exception e
         (log/debug e "Could not list Kafka topics:")
         false))})

  Closeable
  (close [_]
    (reset! running? false)
    (.join worker-thread)))

(defn consume
  [{:keys [offsets indexer timeout topic ^KafkaConsumer consumer index-fn]
    :or {timeout 5000}}]
  (let [records (.poll consumer (Duration/ofMillis timeout))
        records (vec (.records records (str topic)))]
    (index-fn records)
    (when-let [records (seq records)]
      (update-stored-consumer-state offsets consumer records))
    (count records)))

(defn consume-and-block
  [{:keys [offsets indexer pending-records-state timeout topic ^KafkaConsumer consumer accept-fn index-fn]
    :or {timeout 5000}}]
  (assert (and accept-fn index-fn))
  (let [topic-partition (TopicPartition. topic 0)
        _ (when (and (.contains (.paused consumer) topic-partition)
                     (empty? @pending-records-state))
            (log/debug "Resuming" topic)
            (.resume consumer [topic-partition]))
        records (.poll consumer (Duration/ofMillis timeout))
        records (vec (.records records (str topic)))
        pending-records (swap! pending-records-state into records)
        records (->> pending-records
                     (take-while (fn [^ConsumerRecord record]
                                   (let [ready? (accept-fn record)]
                                     (when-not ready?
                                       (when-not (.contains (.paused consumer) topic-partition)
                                         (log/debug "Pausing" topic)
                                         (.pause consumer [topic-partition]))
                                       (log/info "Paused" topic "pending:" (count pending-records)))
                                     ready?)))
                     (vec))]

    (index-fn records)

    (update-stored-consumer-state offsets consumer records)
    (swap! pending-records-state (comp vec (partial drop (count records))))

    (count records)))

(defn start-indexing-consumer
  ^java.io.Closeable
  [{:keys [indexer offsets kafka-config group-id topic accept-fn index-fn]}]
  (let [consumer-config (merge {"group.id" group-id} kafka-config)
        running? (atom true)
        pending-records (atom [])
        worker-thread
        (doto
            (Thread. ^Runnable (fn []
                                 (with-open [consumer (create-consumer consumer-config)]
                                   (subscribe-from-stored-offsets offsets consumer [topic])
                                   (while @running?
                                     (try
                                       (let [opts {:indexer indexer
                                                   :consumer consumer
                                                   :topic topic
                                                   :offsets offsets
                                                   :timeout 1000
                                                   :index-fn index-fn}]
                                         (if accept-fn
                                           (consume-and-block (merge opts {:pending-records-state pending-records
                                                                           :accept-fn accept-fn}))
                                           (consume opts)))
                                       (catch Exception e
                                         (log/error e "Error while consuming and indexing from Kafka:")
                                         (Thread/sleep 500))))))
                     "crux.kafka.indexing-consumer-thread")
            (.start))]
    (map->IndexingConsumer {:running? running?
                            :consumer-config consumer-config
                            :worker-thread worker-thread})))
