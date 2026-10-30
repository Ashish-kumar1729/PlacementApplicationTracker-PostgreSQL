# Understand the PostgreSQL upgrade

## Read in this order

1. `PlacementApplication`: the fields and validation of one record.
2. `ApplicationRepository`: the operations that storage must support.
3. `ApplicationService`: validation, search and status changes.
4. `DatabaseConfig`: environment settings; why passwords are not source code.
5. `JdbcConnectionFactory`: DriverManager opens a Connection.
6. `JdbcApplicationRepository.create`: insert a row and return its generated ID.
7. `findById`, `save`, `deleteById`: SELECT, UPDATE and DELETE.
8. `importAll`: one connection with explicit commit/rollback.
9. `PostgresRepositoryIT`: database behavior verified beyond compilation.

## JDBC objects

| Object | Purpose |
| --- | --- |
| DriverManager | Locates the pgJDBC driver and opens a connection |
| Connection | One database session; holds transaction settings |
| PreparedStatement | SQL with placeholders for bound values |
| ResultSet | Rows returned by a query |
| SQLException | SQL failure with SQLState and underlying cause |

A prepared query looks like `SELECT * FROM applications WHERE id = ?`.
`statement.setInt(1, id)` binds the first placeholder. Company names containing
quotes are bound as text rather than interpreted as SQL syntax.
`try (...)` automatically closes ResultSet, statement and connection.

## Why database IDs and constraints?

Computing max ID + 1 in Java fails when two processes choose the same next ID.
`GENERATED ALWAYS AS IDENTITY` lets PostgreSQL allocate IDs. IDs are identifiers,
not a gap-free count; rollback and failed inserts can leave gaps.

Two processes can both pass a Java duplicate check. The unique index on normalized
company, role and application date is the final authority. SQLState 23505 is a
uniqueness violation; the repository maps it to a readable duplicate message.

## Transactions

A normal single INSERT/UPDATE/DELETE uses JDBC autocommit. The import switches it
off, inserts all rows on the SAME connection and commits only after every insert
succeeds. A later duplicate triggers rollback, undoing earlier inserts in that
transaction. This is different from looping over independent autocommit connections,
which would leave a partial import.

## What tests prove

Local tests check Java validation and the retained file behavior without a server.
Integration tests use PostgreSQL itself to verify SQL constraints, persistence,
quoting, generated IDs, concurrency and rollback. A mock repository cannot prove
those database properties. The `integration` Maven profile deliberately fails if
its database settings are missing.

## Interview questions

- How does JDBC differ from JPA? JDBC exposes SQL and row mapping directly; JPA
  is an ORM specification that manages entity persistence at a higher level.
- Why repository interface? The service uses storage operations without depending
  on whether files or PostgreSQL implement them.
- Why PreparedStatement? Typed parameter binding keeps input separate from SQL.
- How do you avoid duplicate records under concurrency? A database unique index.
- Does @Test prove something passed? No. You must execute the suite and inspect results.
- What changed in complexity? Insert/update/delete now target DB rows rather than
  rewriting the whole file; search/filter/sort still load all rows into Java.
- What remains? Connection pooling, SQL pagination, versioned migrations, auth and
  protection against lost updates. Be honest about these limits.
