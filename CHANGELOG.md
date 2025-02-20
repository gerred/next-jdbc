# Change Log

Only accretive/fixative changes will be made from now on.

## Unreleased Changes

The following changes have been committed to the **master** branch since the 1.0.7 release:

* None.

## Stable Builds

* 2019-09-09 -- 1.0.7
  * Address #60 by supporting simpler schema entry formats: `:table/column` is equivalent to the old `[:table :column :one]` and `[:table/column]` is equivalent to the old `[:table :column :many]`. The older formats will continue to be supported but should be considered deprecated.
  * Added test for using `ANY(?)` and arrays in PostgreSQL for `IN (?,,,?)` style queries. Added a **Tips & Tricks** section to **Friendly SQL Functions** with database-specific suggestions, that starts with this one.
  * Improved documentation in several areas.

* 2019-08-24 -- 1.0.6
  * Fix #54 by improving documentation around data type conversions (and the `ReadableColumn` and `SettableParameter` protocols).
  * Fix #52 by using a US-locale function in the "lower" result set builders to avoid unexpected character changes in column names in locales such as Turkish. If you want the locale-sensitive behavior, pass `clojure.string/lower-case` into one of the "modified" result set builders.
  * Improved documentation around `insert-multi!` and `execute-batch!`.
  * Add `next.jdbc.result-set/as-maps-adapter` and `next.jdbc.result-set/as-arrays-adapter` to provide a way to override the default result set reading behavior of using `.getObject`.
  * Update `org.clojure/test.check` to `"0.10.0"`.

* 2019-08-05 -- 1.0.5
  * Fix #51 by implementing `IPersistentMap` fully for the "mapified" result set inside `plan`. This adds support for `dissoc` and `cons` (which will both realize a row), `count` (which returns the column count but does not realize a row), `empty` (returns an empty hash map without realizing a row), etc.
  * Improved documentation around connection pooling (HikariCP caveats).

* 2019-07-24 -- 1.0.4
  * Fix #50 by adding machinery to test against (embedded) PostgreSQL!
  * Improved documentation for connection pooled datasources (including adding a Component example); clarified the recommendations for globally overriding default options (write a wrapper namespace that suits your usage).
  * Note: this release is primarily to fix the cljdoc.org documentation via repackaging the JAR file.

* 2019-07-23 -- 1.0.3
  * Fix #48 by adding `next.jdbc.connection/->pool` and documenting how to use HikariCP and c3p0 in the Getting Started docs (as well as adding tests for both libraries).
  * Documentation improvements, including examples of extending `ReadableColumn` and `SettableParameter`.
  * Updated test dependencies (testing against more recent versions of several drivers).

* 2019-07-15 -- 1.0.2
  * Fix #47 by refactoring database specs to be a single hash map instead of pouring multiple maps into one.
  * Fix #46 by allowing `:host` to be `:none` which tells `next.jdbc` to omit the host/port section of the JDBC URL, so that local databases can be used with `:dbtype`/`:classname` for database types that `next.jdbc` does not know. Also added `:dbname-separator` and `:host-prefix` to the "db-spec" to allow fine-grained control over how the JDBC URL is assembled.
  * Fix #45 by adding [TimesTen](https://www.oracle.com/database/technologies/related/timesten.html) driver support.
  * Fix #44 so that `insert-multi!` with an empty `rows` vector returns `[]`.
  * Fix #43 by adjusting the spec for `insert-multi!` to "require less" of the `cols` and `rows` arguments.
  * Fix #42 by adding specs for `execute-batch!` and `set-parameters` in `next.jdbc.prepare`.
  * Fix #41 by improving docstrings and documentation, especially around prepared statement handling.
  * Fix #40 by adding `next.jdbc.prepare/execute-batch!`.
  * Added `assert`s in `next.jdbc.sql` as more informative errors for cases that would generate SQL exceptions (from malformed SQL).
  * Added spec for `:order-by` to reflect what is actually permitted.
  * Expose `next.jdbc.connect/dbtypes` as a table of known database types and aliases, along with their class name(s), port, and other JDBC string components.

* 2019-07-03 -- 1.0.1
  * Fix #37 by adjusting the spec for `with-transaction` to "require less" of the `:binding` vector.
  * Fix #36 by adding type hint in `with-transaction` macro.
  * Fix #35 by explaining the database-specific options needed to ensure `insert-multi!` performs a single, batched operation.
  * Fix #34 by explaining save points (in the Transactions documentation).
  * Fix #33 by updating the spec for the example `key-map` in `find-by-keys`, `update!`, and `delete!` to reflect that you cannot pass an empty map to these functions (and added tests to ensure the calls fail with spec errors).

* 2019-06-12 -- 1.0.0 "gold"
  * Address #31 by making `reify`'d objects produce a more informative string representation if they are printed (e.g., misusing `plan` by not reducing it or not mapping an operation over the rows).
  * Fix #26 by exposing `next.jdbc.result-set/datafiable-result-set` so that various `java.sql.DatabaseMetaData` methods that return result metadata information in `ResultSet`s can be easily turned into a fully realized result set.

* 2019-06-04 -- 1.0.0-rc1:
  * Fix #24 by adding return type hints to `next.jdbc` functions.
  * Fix #22 by adding `next.jdbc.optional` with six map builders that omit `NULL` columns from the row hash maps.
  * Documentation improvements (#27, #28, and #29), including changing "connectable" to "transactable" for the `transact` function and the `with-transaction` macro (for consistency with the name of the underlying protocol).
  * Fix #30 by adding `modified` variants of column name functions and builders. The `lower` variants have been rewritten in terms of these new `modified` variants. This adds `:label-fn` and `:qualifier-fn` options that mirror `:column-fn` and `:table-fn` for row builders.

* 2019-05-24 -- 1.0.0-beta1:
  * Set up CircleCI testing (just local DBs for now).
  * Address #21 by adding `next.jdbc.specs` and documenting basic usage.
  * Fix #19 by caching loaded database driver classes.
  * Address #16 by renaming `reducible!` to `plan` (**BREAKING CHANGE!**).
  * Address #3 by deciding to maintain this library outside Clojure Contrib.

## Alpha Builds

* 2019-05-04 -- 1.0.0-alpha13 -- Fix #18 by removing more keys from properties when creating connections.
* 2019-04-26 -- 1.0.0-alpha12 -- Fix #17 by renaming `:next.jdbc/sql-string` to `:next.jdbc/sql-params` (**BREAKING CHANGE!**) and pass whole vector.
* 2019-04-24 -- 1.0.0-alpha11 -- Rename `:gen-fn` to `:builder-fn` (**BREAKING CHANGE!**); Fix #13 by adding documentation for `datafy`/`nav`/`:schema`; Fix #15 by automatically adding `:next.jdbc/sql-string` (as of 1.0.0-alpha12: `:next.jdbc/sql-params`) into the options hash map, so custom builders can depend on the SQL string.
* 2019-04-22 -- 1.0.0-alpha9 -- Fix #14 by respecting `:gen-fn` (as of 1.0.0-alpha11: `:builder-fn`) in `execute-one` for `PreparedStatement`.
* 2019-04-21 -- 1.0.0-alpha8 -- Initial publicly announced release.
