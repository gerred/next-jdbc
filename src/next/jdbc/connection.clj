;; copyright (c) 2018-2019 Sean Corfield, all rights reserved

(ns next.jdbc.connection
  "Standard implementations of `get-datasource` and `get-connection`."
  (:require [clojure.java.data :refer [to-java]]
            [next.jdbc.protocols :as p])
  (:import (java.sql Connection DriverManager)
           (javax.sql DataSource)
           (java.util Properties)))

(set! *warn-on-reflection* true)

(def dbtypes
  "A map of all known database types (including aliases) to the class name(s)
  and port that `next.jdbc` supports out of the box. For databases that have
  non-standard prefixes for the `:dbname` and/or `:host` values in the JDBC
  string, this table includes `:dbname-separator` and/or `:host-prefix`. The
  default prefix for `:dbname` is either `/` or `:` and for `:host` it is `//`.
  For local databases, with no `:host`/`:port` segment in their JDBC URL, a
  value of `:none` is provided for `:host` in this table.

  For known database types, you can use `:dbtype` (and omit `:classname`).

  If you want to use a database that is not in this list, you can specify
  a new `:dbtype` along with the class name of the JDBC driver in `:classname`.
  You will also need to specify `:port`. For example:

     `{:dbtype \"acme\" :classname \"com.acme.JdbcDriver\" ...}`

  The value of `:dbtype` should be the string that the driver is associated
  with in the JDBC URL, i.e., the value that comes between the `jdbc:`
  prefix and the `://<host>...` part. In the above example, the JDBC URL
  that would be generated would be `jdbc:acme://<host>:<port>/<dbname>`.

  If you want `next.jdbc` to omit the host/port part of the URL, specify
  `:host :none`, which would produce a URL like: `jdbc:acme:<dbname>`,
  which allows you to work with local databases (or drivers that do not
  need host/port information).

  The default prefix for the host name (or IP address) is `//`. You
  can override this via the `:host-prefix` option.

  The default separator between the host/port and the database name is `/`.
  The default separator between the subprotocol and the database name,
  for local databases with no host/port, is `:`. You can override this
  via the `:dbname-separator` option.

  JDBC drivers are not provided by `next.jdbc` -- you need to specify the
  driver(s) you need as additional dependencies in your project. For
  example:

     `[com.acme/jdbc \"1.2.3\"] ; lein/boot`

  or:

     `{com.acme/jdbc {:mvn/version \"1.2.3\"}} ; CLI/deps.edn`

  Note: the `:classname` value can be a string or a vector of strings. If
  a vector of strings is provided, an attempt will be made to load each
  named class in order, until one succeeds. This allows for a given `:dbtype`
  to be used with different versions of a JDBC driver, if the class name
  has changed over time (such as with MySQL)."
  {"derby"           {:classname "org.apache.derby.jdbc.EmbeddedDriver"
                      :host :none}
   "h2"              {:classname "org.h2.Driver"
                      :host :none}
   "h2:mem"          {:classname "org.h2.Driver"}
   "hsql"            {:classname "org.hsqldb.jdbcDriver"
                      :alias-for "hsqldb"
                      :host :none}
   "hsqldb"          {:classname "org.hsqldb.jdbcDriver"
                      :host :none}
   "jtds"            {:classname "net.sourceforge.jtds.jdbc.Driver"
                      :alias-for "jtds:sqlserver"
                      :port 1433}
   "jtds:sqlserver"  {:classname "net.sourceforge.jtds.jdbc.Driver"
                      :port 1433}
   "mssql"           {:classname "com.microsoft.sqlserver.jdbc.SQLServerDriver"
                      :alias-for "sqlserver"
                      :dbname-separator ";DATABASENAME="
                      :port 1433}
   "mysql"           {:classname ["com.mysql.cj.jdbc.Driver"
                                  "com.mysql.jdbc.Driver"]
                      :port 3306}
   "oracle"          {:classname "oracle.jdbc.OracleDriver"
                      :alias-for "oracle:thin"
                      :host-prefix "@"
                      :port 1521}
   "oracle:oci"      {:classname "oracle.jdbc.OracleDriver"
                      :host-prefix "@"
                      :port 1521}
   "oracle:sid"      {:classname "oracle.jdbc.OracleDriver"
                      :alias-for "oracle:thin"
                      :dbname-separator ":"
                      :host-prefix "@"
                      :port 1521}
   "oracle:thin"     {:classname "oracle.jdbc.OracleDriver"
                      :host-prefix "@"
                      :port 1521}
   "postgres"        {:classname "org.postgresql.Driver"
                      :alias-for "postgresql"
                      :port 5432}
   "postgresql"      {:classname "org.postgresql.Driver"
                      :port 5432}
   "pgsql"           {:classname "com.impossibl.postgres.jdbc.PGDriver"}
   "redshift"        {:classname "com.amazon.redshift.jdbc.Driver"}
   "sqlite"          {:classname "org.sqlite.JDBC"
                      :host :none}
   "sqlserver"       {:classname "com.microsoft.sqlserver.jdbc.SQLServerDriver"
                      :dbname-separator ";DATABASENAME="
                      :port 1433}
   "timesten:client" {:classname "com.timesten.jdbc.TimesTenClientDriver"
                      :dbname-separator ":dsn="
                      :host :none}
   "timesten:direct" {:classname "com.timesten.jdbc.TimesTenDriver"
                      :dbname-separator ":dsn="
                      :host :none}})

(defn- ^Properties as-properties
  "Convert any seq of pairs to a `java.util.Properties` instance."
  [m]
  (let [p (Properties.)]
    (doseq [[k v] m]
      (.setProperty p (name k) (str v)))
    p))

(defn- get-driver-connection
  "Common logic for loading the designated JDBC driver class and
  obtaining the appropriate `Connection` object."
  [url etc]
  (DriverManager/getConnection url (as-properties etc)))

(def ^:private driver-cache
  "An optimization for repeated calls to get-datasource, or for get-connection
  called on a db-spec hash map, so that we only try to load the classes once."
  (atom {}))

(defn- spec->url+etc
  "Given a database spec, return a JDBC URL and a map of any additional options."
  [{:keys [dbtype dbname host port classname
           dbname-separator host-prefix]
    :as db-spec}]
  (let [;; allow aliases for dbtype
        subprotocol (-> dbtype dbtypes :alias-for (or dbtype))
        host        (or host (-> dbtype dbtypes :host) "127.0.0.1")
        port        (or port (-> dbtype dbtypes :port))
        db-sep      (or dbname-separator (-> dbtype dbtypes :dbname-separator (or "/")))
        local-sep   (or dbname-separator (-> dbtype dbtypes :dbname-separator (or ":")))
        url (cond (#{"h2"} subprotocol)
                  (str "jdbc:" subprotocol local-sep
                       (if (re-find #"^([A-Za-z]:)?[\./\\]" dbname)
                         ;; DB name starts with relative or absolute path
                         dbname
                         ;; otherwise make it local
                         (str "./" dbname)))

                  (#{"h2:mem"} subprotocol)
                  (str "jdbc:" subprotocol local-sep dbname ";DB_CLOSE_DELAY=-1")

                  (= :none host)
                  (str "jdbc:" subprotocol local-sep dbname)

                  :else
                  (str "jdbc:" subprotocol ":"
                       (or host-prefix (-> dbtype dbtypes :host-prefix (or "//")))
                       host
                       (when port (str ":" port))
                       db-sep dbname))
        etc (dissoc db-spec
                    :dbtype :dbname :host :port :classname
                    :dbname-separator :host-prefix)]
    ;; verify the datasource is loadable
    (if-let [class-name (or classname (-> dbtype dbtypes :classname))]
      (swap! driver-cache update class-name
             #(if % %
                (do
                  ;; force DriverManager to be loaded
                  (DriverManager/getLoginTimeout)
                  (if (string? class-name)
                    (clojure.lang.RT/loadClassForName class-name)
                    (loop [[clazz & more] class-name]
                      (let [loaded
                            (try
                              (clojure.lang.RT/loadClassForName clazz)
                              (catch Exception e
                                e))]
                        (if (instance? Throwable loaded)
                          (if (seq more)
                            (recur more)
                            (throw loaded))
                          loaded)))))))
      (throw (ex-info (str "Unknown dbtype: " dbtype) db-spec)))
    [url etc]))

(defn ->pool
  "Given a (connection pooled datasource) class and a database spec, return a
  connection pool object built from that class and the database spec.

  Assumes the `clazz` has a `.setJdbcUrl` method (which HikariCP and c3p0 do).

  If you already have a JDBC URL and want to use this method, pass `:jdbcUrl`
  in the database spec (instead of `:dbtype`, `:dbname`, etc).

  Properties for the connection pool object can be passed as mixed case
  keywords that correspond to setter methods (just as `:jdbcUrl` maps to
  `.setJdbcUrl`). `clojure.java.data/to-java` is used to construct the
  object and call the setters.

  Note that the result is not type-hinted (because there's no common base
  class or interface that can be assumed). In particular, connection pooled
  datasource objects may need to be closed but they don't necessarily implement
  `java.io.Closeable` (HikariCP does, c3p0 does not)."
  [clazz db-spec]
  (if (:jdbcUrl db-spec)
    (to-java clazz db-spec)
    (let [[url etc] (spec->url+etc db-spec)]
      (to-java clazz (assoc etc :jdbcUrl url)))))

(defn- string->url+etc
  "Given a JDBC URL, return it with an empty set of options with no parsing."
  [s]
  [s {}])

(defn- url+etc->datasource
  "Given a JDBC URL and a map of options, return a `DataSource` that can be
  used to obtain a new database connection."
  [[url etc]]
  (reify DataSource
    (getConnection [_]
                   (get-driver-connection url etc))
    (getConnection [_ username password]
                   (get-driver-connection url
                                          (assoc etc
                                                 :user username
                                                 :password password)))
    (toString [_] url)))

(defn- make-connection
  "Given a `DataSource` and a map of options, get a connection and update it
  as specified by the options.

  The options supported are:
  * `:auto-commit` -- whether the connection should be set to auto-commit or not;
      without this option, the defaut is `true` -- connections will auto-commit,
  * `:read-only` -- whether the connection should be set to read-only mode."
  ^Connection
  [^DataSource datasource opts]
  (let [^Connection connection (.getConnection datasource)]
    (when (contains? opts :auto-commit)
      (.setAutoCommit connection (boolean (:auto-commit opts))))
    (when (contains? opts :read-only)
      (.setReadOnly connection (boolean (:read-only opts))))
    connection))

(extend-protocol p/Sourceable
  clojure.lang.Associative
  (get-datasource [this]
                  (url+etc->datasource (spec->url+etc this)))
  javax.sql.DataSource
  (get-datasource [this] this)
  String
  (get-datasource [this]
                  (url+etc->datasource (string->url+etc this))))

(extend-protocol p/Connectable
  javax.sql.DataSource
  (get-connection [this opts] (make-connection this opts))
  java.sql.PreparedStatement
  ;; note: options are ignored and this should not be closed independently
  ;; of the PreparedStatement to which it belongs: this done to allow
  ;; datafy/nav across a PreparedStatement only...
  (get-connection [this _] (.getConnection this))
  Object
  (get-connection [this opts] (p/get-connection (p/get-datasource this) opts)))
