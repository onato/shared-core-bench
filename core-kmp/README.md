# core-kmp

The Kotlin Multiplatform core. Its storage is SQLDelight 2.4.1 over SQLite, with the queries in
`src/commonMain/sqldelight/bench/core/db/Product.sq`.

## SQL that SQLDelight's dialect rejects

The build uses SQLDelight's default dialect, which parses a subset of what SQLite runs. Each of the
forms below is valid SQLite (Android's minSdk 26 ships 3.18) and still fails the SQLDelight build,
so `Product.sq` writes it another way:

| SQLite accepts | SQLDelight rejects it; the core uses | Found in |
|---|---|---|
| `INSERT … ON CONFLICT (id) DO UPDATE SET …` | `INSERT OR REPLACE INTO …` | #60, #164 |
| Row values: `(sort_key, id) > (?, ?)` | `sort_key > (SELECT …) OR (sort_key = (SELECT …) AND id > ?)`, two scalar subqueries | #154 |
| `WHERE NOT deleted` | `WHERE deleted = 0` | #164 |

`INSERT OR REPLACE` deletes the old row before inserting the new one. That is safe here only because
nothing references `product` with a foreign key and the upsert writes every column.

Before reaching for SQLite syntax that isn't already in `Product.sq`, expect the dialect may reject
it and check with `make test-core CORE=kmp` early, before building on it.

## Ports of this SQL

The Swift storage port (#283) runs the same queries on raw SQLite, which would accept the forms on
the left. It copies the SQL in `Product.sq` as written, not the syntax SQLite would accept, so the
cores keep executing the same statements and benchmark the same work.
