# Validation performed for version 2

## Passed

- Maven package: Java 17 compilation, test compilation and executable JAR packaging.
- 13 JUnit tests, zero failures/errors/skips: 12 service/configuration tests and one
  file regression test that executes the original 24 file/console checks.
- Actual run.sh launcher in explicit file mode.
- Packaged JAR: add a record, exit and reload it in a new process.
- Packaged JAR: missing database credentials fail clearly.
- Packaged JAR: bundled JDBC driver loads and an unavailable server produces a
  database error rather than silently switching to files.
- Shell syntax validation and ZIP integrity check.

## Requires PostgreSQL verification

13 PostgreSQL integration tests are included and compile, but were NOT executed
against a live PostgreSQL server during preparation: no usable database server
was available in this environment. Do not interpret successful local tests as
proof that the database suite passed.

Create the separate test database described in README.md, load its credentials,
and run `mvn -Pintegration verify`. Missing test credentials cause failure rather
than a silently skipped suite. The included GitHub Actions workflow provisions a
PostgreSQL service for this verification when you push the project. It has not
been run on your GitHub account here.

No files on your computer, local PostgreSQL databases or GitHub repositories were
modified. The upgrade uses the original project supplied in this conversation.
