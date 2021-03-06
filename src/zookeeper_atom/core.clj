;; Copyright (c) Torsten Becker, 2014. All rights reserved.  The use
;; and distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;; which can be found in the file epl.html at the root of this
;; distribution.  By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns zookeeper-atom.core
  (:refer-clojure :rename {atom clj-atom
                           swap! clj-swap!
                           reset! clj-reset!
                           compare-and-set! clj-compare-and-set!})
  (require [zookeeper :as zoo]
           [zookeeper.data :as zoo.data]
           [clojure.tools.logging :refer [debug]]
           [clojure.edn :as edn]
           [clojure.string :as string])
  (import (org.apache.zookeeper KeeperException KeeperException$Code)))

(defn- decode
  "Decode bytes back into a Clojure data structure."
  [bytes]
  (when bytes
    (edn/read-string (zoo.data/to-string bytes))))

(defn- encode
  "Encode Clojure a data structure into bytes."
  [data]
  (when data
    (let [encoded (zoo.data/to-bytes (pr-str data))]
      (try
        (decode encoded)
        encoded
        (catch Exception e
          (throw (ex-info "Can't encode data as EDN" {:data data} e)))))))

(defn- all-prefixes
  "Generate all prefixes for a given path, for example:
   (all-prefixes /123/asd/bbbb) => [/123, /123/asd, /123/asd/bbbb]"
  [^String path]
  (when (.startsWith path "/")
    (->> path
      (iterate #(string/replace % #"/[^/]*$" ""))
      (take-while seq)
      reverse)))

(defrecord Atom [cache client path]
  clojure.lang.IDeref
    (deref [this]
      (-> this :cache deref :data)))

; Somehow we have to overwrite this or Clojure fails with
;   IllegalArgumentException Multiple methods in multimethod 'print-method' ...
(defmethod print-method Atom [this ^java.io.Writer w]
  (.write w (str "#<" (-> this .getClass .getName) " path:" (:path this) ">")))

(def connect
  "Connect to zookeeper, accepts same arguments as zookeeper/connect.
   Returns a new zookeeper client."
  zoo/connect)

(defn- set-value
  "Set new data for zookeeper path by storing the encoded `value`,
   Optionally calls `retry-fn` if the remote version does not match `version`."
  [client path value version]
  (let [bytes (encode value)]
    (try
      (do
        (debug path version "=>" value)
        (let [response (zoo/set-data client path bytes version)]
          (debug "ZK said" response)
          value))
      (catch KeeperException ex
        (if (= (.code ex) KeeperException$Code/BADVERSION)
          ::bad-version
          (throw ex))))))

(defn- init
  "Only sets a value if the znode is newly created (aka version=0).
   Returns the zookeeper-atom."
  [^Atom atom value]
  (let [{:keys [client path]} atom
        version 0]
    (set-value client path value version))
    atom)

(defn swap!
  "Swap a zookeeper-atom's value with the same semantics as Clojure's swap!.
   Returns the zookeeper-atom's value after swapping."
  [^Atom atom f & args]
  (let [{:keys [client path cache]} atom
        {:keys [data version]} @cache
        swapped (apply f data args)
        result (set-value client path swapped version)]
    (if (= ::bad-version result)
      (do
        (debug "caught bad-version, will retry")
        (recur atom f args))
      result)))

(defn reset!
  "Reset a zookeeper-atom's value without regard for the value."
  [^Atom atom value]
  (swap! atom (constantly value)))

(defn compare-and-set!
  [^Atom atom oldval newval]
  (loop []
    (let [{:keys [client path]} atom
          currval (-> (zoo/data client path) :data decode)]
      (if (= currval oldval)
        (reset! atom newval)
        (recur)))))

(defn data-version
  "Returns the data version of the znode. Mostly useful for debugging."
  [^Atom atom]
  (-> atom :cache deref :version))

(defn- znode-data-watcher
  "Returns a function that continuously watches a zookeeper-atom's value
   for remote changes and updates the :cache field with these changes."
  [^Atom atom]
  (fn [{:keys [event-type keeper-state path]}]
    (debug "Change" event-type keeper-state path)
    (assert (or (nil? path) (-> (:path atom) (= path))))
    (let [{:keys [client path cache]} atom
          z-data (zoo/data client path :watcher (znode-data-watcher atom))
          data (-> z-data :data decode)
          version (-> z-data :stat :version)]
      (debug "ZK watch" version "=>" data)
      (clj-swap! cache assoc :data data :version version)
      nil)))

(defn atom
  "Create a new zookeeper-atom. Optionally sets an `initial-value`.
   The `initial-value` will only be set if the znode at `path` is
   being newly created with this call.
   Existing values will not be overwritten by `initial-value`."
  ([client ^String path]
    (doseq [node (all-prefixes path)]
      (zoo/create client node :persistent? true))
    (let [cache (clj-atom {})
          this (Atom. cache client path)]
      ((znode-data-watcher this) nil)
      this))
  ([client ^String path initial-value]
    (-> (atom client path) (init initial-value))))
