(ns crux.kv-store)

(defprotocol CruxKvStore
  (open [this])

  (value [db k])

  (seek [db k])

  (seek-and-iterate [this key-pred k])

  (store [this k v])

  (merge! [this k v])

  (close [this])

  (destroy [this]))
