;; copyright (c) 2019 Sean Corfield, all rights reserved

(ns next.jdbc.connection-test
  "Tests for the main hash map spec to JDBC URL logic and the get-datasource
  and get-connection protocol implementations.

  At some point, the datasource/connection tests should probably be extended
  to accept EDN specs from an external source (environment variables?)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc.connection :as c]
            [next.jdbc.protocols :as p])
  (:import (com.zaxxer.hikari HikariDataSource)
           (com.mchange.v2.c3p0 ComboPooledDataSource PooledDataSource)))

(set! *warn-on-reflection* true)

(def ^:private db-name "clojure_test")

(deftest test-aliases-and-defaults
  (testing "aliases"
    (is (= (#'c/spec->url+etc {:dbtype "hsql" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "hsqldb" :dbname db-name})))
    (is (= (#'c/spec->url+etc {:dbtype "jtds" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "jtds:sqlserver" :dbname db-name})))
    (is (= (#'c/spec->url+etc {:dbtype "mssql" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "sqlserver" :dbname db-name})))
    (is (= (#'c/spec->url+etc {:dbtype "oracle" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "oracle:thin" :dbname db-name})))
    (is (= (#'c/spec->url+etc {:dbtype "oracle:sid" :dbname db-name})
           (-> (#'c/spec->url+etc {:dbtype "oracle:thin" :dbname db-name})
               ;; oracle:sid uses : before DB name, not /
               (update 0 str/replace (re-pattern (str "/" db-name)) (str ":" db-name)))))
    (is (= (#'c/spec->url+etc {:dbtype "oracle:oci" :dbname db-name})
           (-> (#'c/spec->url+etc {:dbtype "oracle:thin" :dbname db-name})
               ;; oracle:oci and oracle:thin only differ in the protocol
               (update 0 str/replace #":thin" ":oci"))))
    (is (= (#'c/spec->url+etc {:dbtype "postgres" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "postgresql" :dbname db-name}))))
  (testing "default ports"
    (is (= (#'c/spec->url+etc {:dbtype "jtds:sqlserver" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "jtds:sqlserver" :dbname db-name :port 1433})))
    (is (= (#'c/spec->url+etc {:dbtype "mysql" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "mysql" :dbname db-name :port 3306})))
    (is (= (#'c/spec->url+etc {:dbtype "oracle:oci" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "oracle:oci" :dbname db-name :port 1521})))
    (is (= (#'c/spec->url+etc {:dbtype "oracle:sid" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "oracle:sid" :dbname db-name :port 1521})))
    (is (= (#'c/spec->url+etc {:dbtype "oracle:thin" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "oracle:thin" :dbname db-name :port 1521})))
    (is (= (#'c/spec->url+etc {:dbtype "postgresql" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "postgresql" :dbname db-name :port 5432})))
    (is (= (#'c/spec->url+etc {:dbtype "sqlserver" :dbname db-name})
           (#'c/spec->url+etc {:dbtype "sqlserver" :dbname db-name :port 1433})))))

(deftest custom-dbtypes
  (is (= ["jdbc:acme:my-db" {}]
         (#'c/spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                             :dbname "my-db" :host :none})))
  (is (= ["jdbc:acme://127.0.0.1/my-db" {}]
         (#'c/spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                             :dbname "my-db"})))
  (is (= ["jdbc:acme://12.34.56.70:1234/my-db" {}]
         (#'c/spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                             :dbname "my-db" :host "12.34.56.70" :port 1234})))
  (is (= ["jdbc:acme:dsn=my-db" {}]
         (#'c/spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                             :dbname "my-db" :host :none
                             :dbname-separator ":dsn="})))
  (is (= ["jdbc:acme:(*)127.0.0.1/my-db" {}]
         (#'c/spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                             :dbname "my-db"
                             :host-prefix "(*)"})))
  (is (= ["jdbc:acme:(*)12.34.56.70:1234/my-db" {}]
         (#'c/spec->url+etc {:dbtype "acme" :classname "java.lang.String"
                             :dbname "my-db" :host "12.34.56.70" :port 1234
                             :host-prefix "(*)"}))))

;; these are the 'local' databases that we can always test against
(def test-db-type ["derby" "h2" "h2:mem" "hsqldb" "sqlite"])

(def test-dbs
  (for [db test-db-type]
    (cond-> {:dbtype db :dbname (str db-name "_" (str/replace db #":" "_"))}
      (= "derby" db)
      (assoc :create true))))

(deftest test-get-connection
  (doseq [db test-dbs]
    (println 'test-get-connection (:dbtype db))
    (testing "datasource via Associative"
      (let [ds (p/get-datasource db)]
        (is (instance? javax.sql.DataSource ds))
        (is (str/index-of (pr-str ds) (str "jdbc:" (:dbtype db))))
        ;; checks get-datasource on a DataSource is identity
        (is (identical? ds (p/get-datasource ds)))
        (with-open [con (p/get-connection ds {})]
          (is (instance? java.sql.Connection con)))))
    (testing "datasource via String"
      (let [[url _] (#'c/spec->url+etc db)
            ds (p/get-datasource url)]
        (is (instance? javax.sql.DataSource ds))
        (is (str/index-of (pr-str ds) url))
        (with-open [con (p/get-connection ds {})]
          (is (instance? java.sql.Connection con)))))
    (testing "datasource via HikariCP"
      ;; the type hint is only needed because we want to call .close
      (with-open [^HikariDataSource ds (c/->pool HikariDataSource db)]
        (is (instance? javax.sql.DataSource ds))
        ;; checks get-datasource on a DataSource is identity
        (is (identical? ds (p/get-datasource ds)))
        (with-open [con (p/get-connection ds {})]
          (is (instance? java.sql.Connection con)))))
    (testing "datasource via c3p0"
      ;; the type hint is only needed because we want to call .close
      (with-open [^PooledDataSource ds (c/->pool ComboPooledDataSource db)]
        (is (instance? javax.sql.DataSource ds))
        ;; checks get-datasource on a DataSource is identity
        (is (identical? ds (p/get-datasource ds)))
        (with-open [con (p/get-connection ds {})]
          (is (instance? java.sql.Connection con)))))
    (testing "connection via map (Object)"
      (with-open [con (p/get-connection db {})]
        (is (instance? java.sql.Connection con))))))
