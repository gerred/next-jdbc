;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc.prepare
  "Mostly an implementation namespace for how `PreparedStatement` objects are
  created by the next generation java.jdbc library.

  `set-parameters` is public and may be useful if you have a `PreparedStatement`
  that you wish to reuse and (re)set the parameters on it.

  `execute-batch!` provides a way to add batches of parameters to a
  `PreparedStatement` and then execute it in batch mode (via `.executeBatch`).

  Defines the `SettableParameter` protocol for converting Clojure values
  to database-specific values."
  (:require [next.jdbc.protocols :as p])
  (:import (java.sql Connection
                     PreparedStatement
                     ResultSet
                     Statement)))

(set! *warn-on-reflection* true)

(defprotocol SettableParameter :extend-via-metadata true
  "Protocol for setting SQL parameters in statement objects, which
  can convert from Clojure values. The default implementation just
  calls `.setObject` on the parameter value. It can be extended to
  use other methods of `PreparedStatement` to convert and set parameter
  values. Extension via metadata is supported."
  (set-parameter [val stmt ix]
    "Convert a Clojure value into a SQL value and store it as the ix'th
    parameter in the given SQL statement object."))

(extend-protocol SettableParameter
  Object
  (set-parameter [v ^PreparedStatement s ^long i]
    (.setObject s i v))

  nil
  (set-parameter [_ ^PreparedStatement s ^long i]
    (.setObject s i nil)))

(defn set-parameters
  "Given a `PreparedStatement` and a vector of parameter values, update the
  `PreparedStatement` with those parameters and return it."
  ^java.sql.PreparedStatement
  [^PreparedStatement ps params]
  (when (seq params)
    (loop [[p & more] params i 1]
      (set-parameter p ps i)
      (when more
        (recur more (inc i)))))
  ps)

(def ^{:private true
       :doc "Map friendly `:concurrency` values to `ResultSet` constants."}
  result-set-concurrency
  {:read-only ResultSet/CONCUR_READ_ONLY
   :updatable ResultSet/CONCUR_UPDATABLE})

(def ^{:private true
       :doc "Map friendly `:cursors` values to `ResultSet` constants."}
  result-set-holdability
  {:hold ResultSet/HOLD_CURSORS_OVER_COMMIT
   :close ResultSet/CLOSE_CURSORS_AT_COMMIT})

(def ^{:private true
       :doc "Map friendly `:type` values to `ResultSet` constants."}
  result-set-type
  {:forward-only ResultSet/TYPE_FORWARD_ONLY
   :scroll-insensitive ResultSet/TYPE_SCROLL_INSENSITIVE
   :scroll-sensitive ResultSet/TYPE_SCROLL_SENSITIVE})

(defn- ^{:tag (class (into-array String []))} string-array
  [return-keys]
  (into-array String return-keys))

(defn create
  "This is an implementation detail -- use `next.jdbc/prepare` instead.

  Given a `Connection`, a SQL string, some parameters, and some options,
  return a `PreparedStatement` representing that."
  ^java.sql.PreparedStatement
  [^Connection con ^String sql params
   {:keys [return-keys result-type concurrency cursors
           fetch-size max-rows timeout]}]
  (let [^PreparedStatement ps
        (cond
         return-keys
         (do
           (when (or result-type concurrency cursors)
             (throw (IllegalArgumentException.
                     (str ":concurrency, :cursors, and :result-type "
                          "may not be specified with :return-keys."))))
           (if (vector? return-keys)
             (let [key-names (string-array return-keys)]
               (try
                 (try
                   (.prepareStatement con sql key-names)
                   (catch Exception _
                     ;; assume it is unsupported and try regular generated keys:
                     (.prepareStatement con sql Statement/RETURN_GENERATED_KEYS)))
                 (catch Exception _
                   ;; assume it is unsupported and try basic PreparedStatement:
                   (.prepareStatement con sql))))
             (try
               (.prepareStatement con sql Statement/RETURN_GENERATED_KEYS)
               (catch Exception _
                 ;; assume it is unsupported and try basic PreparedStatement:
                 (.prepareStatement con sql)))))

         (and result-type concurrency)
         (if cursors
           (.prepareStatement con sql
                              (get result-set-type result-type result-type)
                              (get result-set-concurrency concurrency concurrency)
                              (get result-set-holdability cursors cursors))
           (.prepareStatement con sql
                              (get result-set-type result-type result-type)
                              (get result-set-concurrency concurrency concurrency)))

         (or result-type concurrency cursors)
         (throw (IllegalArgumentException.
                 (str ":concurrency, :cursors, and :result-type "
                      "may not be specified independently.")))
         :else
         (.prepareStatement con sql))]
    (when fetch-size
      (.setFetchSize ps fetch-size))
    (when max-rows
      (.setMaxRows ps max-rows))
    (when timeout
      (.setQueryTimeout ps timeout))
    (set-parameters ps params)))

(extend-protocol p/Preparable
  java.sql.Connection
  (prepare [this sql-params opts]
           (create this (first sql-params) (rest sql-params) opts)))

(defn execute-batch!
  "Given a `PreparedStatement` and a vector containing parameter groups,
  i.e., a vector of vector of parameters, use `.addBatch` to add each group
  of parameters to the prepared statement (via `set-parameters`) and then
  call `.executeBatch`. A vector of update counts is returned.

  An options hash map may also be provided, containing `:batch-size` which
  determines how to partition the parameter groups for submission to the
  database. If omitted, all groups will be submitted as a single command.
  If you expect the update counts to be larger than `Integer/MAX_VALUE`,
  you can specify `:large true` and `.executeLargeBatch` will be called
  instead.

  Returns a Clojure vector of update counts.

  May throw `java.sql.BatchUpdateException` if any part of the batch fails.
  You may be able to call `.getUpdateCounts` on that exception object to
  get more information about which parts succeeded and which failed.

  For additional caveats and database-specific options you may need, see:
  https://cljdoc.org/d/seancorfield/next.jdbc/CURRENT/doc/getting-started/prepared-statements#caveats

  Not all databases support batch execution."
  ([ps param-groups]
   (execute-batch! ps param-groups {}))
  ([^PreparedStatement ps param-groups opts]
   (let [params (if-let [n (:batch-size opts)]
                  (if (and (number? n) (pos? n))
                    (partition-all n param-groups)
                    (throw (IllegalArgumentException.
                            ":batch-size must be positive")))
                  [param-groups])]
     (into []
           (mapcat (fn [group]
                     (run! #(.addBatch (set-parameters ps %)) group)
                     (if (:large opts)
                       (.executeLargeBatch ps)
                       (.executeBatch ps))))
           params))))
