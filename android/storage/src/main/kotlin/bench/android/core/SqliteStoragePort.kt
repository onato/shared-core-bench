package bench.android.core

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import bench.core.CoreStats
import bench.core.ProductDetail
import bench.core.ProductSummary
import bench.core.Query
import bench.core.Sort
import bench.core.StorageError
import bench.core.StoragePort
import bench.core.StorageResult
import bench.core.Stored

/** The app's [StoragePort]: the core's schema and SQL (`Product.sq`) on the bundled SQLite. */
class SqliteStoragePort private constructor(
    private val connection: SQLiteConnection,
) : StoragePort,
    AutoCloseable by connection {
    override fun transaction(block: () -> StorageResult<Unit>): StorageResult<Unit> =
        catching {
            connection.execSQL("BEGIN")
            val result = block()
            val committed =
                if (result is StorageError) {
                    result
                } else {
                    catching {
                        connection.execSQL("COMMIT")
                        result
                    }
                }
            // A failed COMMIT, such as one blocked by a reader, leaves the transaction open.
            if (committed is StorageError) connection.execSQL("ROLLBACK")
            committed
        }

    override fun upsert(
        items: List<ProductDetail>,
        generation: Long,
    ): StorageResult<Unit> =
        catching {
            items.forEach { item ->
                with(item) {
                    val row =
                        listOf(id, title, description, category, brand, price, discountPercentage, discountedPrice)
                    run(UPSERT, row + listOf(rating, stock, thumbnail, generation)) { step() }
                    run("DELETE FROM product_tag WHERE product_id = ?", listOf(id)) { step() }
                    tags.forEachIndexed { position, tag ->
                        run(INSERT_TAG, listOf(id, position.toLong(), tag)) { step() }
                    }
                }
            }
            Stored(Unit)
        }

    override fun upsertOne(
        item: ProductDetail,
        generation: Long,
    ) = upsert(listOf(item), generation)

    override fun markMissingDeleted(generation: Long): StorageResult<Unit> =
        catching {
            run("UPDATE product SET deleted = 1 WHERE seen_generation < ?", listOf(generation)) { step() }
            Stored(Unit)
        }

    override fun query(
        query: Query,
        cursor: Long?,
        size: Int,
    ): StorageResult<List<ProductSummary>> =
        catching {
            Stored(
                with(query) {
                    run(PAGE_AFTER, listOf(cursor, sortKey(sort), text, category, minPrice, maxPrice, size.toLong())) {
                        rows { ProductSummary(long(), text(), double(), double(), double(), text()) }
                    }
                },
            )
        }

    override fun get(id: Long): StorageResult<ProductDetail?> =
        catching {
            val tags =
                run("SELECT tag FROM product_tag WHERE product_id = ? ORDER BY position", listOf(id)) {
                    rows { text() }
                }
            Stored(
                run(BY_ID, listOf(id)) {
                    rows {
                        ProductDetail(
                            long(),
                            text(),
                            text(),
                            text(),
                            optionalText(),
                            double(),
                            double(),
                            double(),
                            long(),
                            text(),
                            tags,
                        )
                    }.singleOrNull()
                },
            )
        }

    override fun categories(): StorageResult<List<String>> =
        catching {
            Stored(run("SELECT DISTINCT category FROM product WHERE deleted = 0 ORDER BY category") { rows { text() } })
        }

    override fun stats(): StorageResult<CoreStats> =
        catching {
            Stored(
                CoreStats(
                    productCount = count("SELECT count(*) FROM product"),
                    deletedCount = count("SELECT count(*) FROM product WHERE deleted = 1"),
                ),
            )
        }

    private fun count(sql: String): Long = run(sql) { rows { long() }.single() }

    private fun <T> run(
        sql: String,
        args: List<Any?> = emptyList(),
        block: SQLiteStatement.() -> T,
    ): T =
        connection.prepare(sql).use { statement ->
            args.forEachIndexed { index, arg -> statement.bind(index + 1, arg) }
            statement.block()
        }

    companion object {
        fun open(path: String): SqliteStoragePort {
            val connection = BundledSQLiteDriver().open(path)
            SCHEMA.forEach(connection::execSQL)
            return SqliteStoragePort(connection)
        }
    }
}

private inline fun <T> catching(call: () -> StorageResult<T>): StorageResult<T> =
    try {
        call()
    } catch (failure: SQLiteException) {
        StorageError(failure.toString())
    }

private fun SQLiteStatement.bind(
    index: Int,
    value: Any?,
) = when (value) {
    null -> bindNull(index)
    is Long -> bindLong(index, value)
    is Double -> bindDouble(index, value)
    else -> bindText(index, value.toString())
}

private fun <T> SQLiteStatement.rows(row: Columns.() -> T): List<T> =
    buildList {
        while (step()) add(Columns(this@rows).row())
    }

/** A result row's columns, read in the order the query selects them. */
private class Columns(
    private val statement: SQLiteStatement,
) {
    private var next = 0

    fun long() = statement.getLong(next++)

    fun double() = statement.getDouble(next++)

    fun text() = statement.getText(next++)

    fun optionalText(): String? {
        val index = next++
        return if (statement.isNull(index)) null else statement.getText(index)
    }
}

private fun sortKey(sort: Sort) =
    when (sort) {
        Sort.Id -> "id"
        Sort.PriceAsc -> "price-asc"
        Sort.PriceDesc -> "price-desc"
        Sort.RatingDesc -> "rating-desc"
    }

private val SCHEMA =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS product (
            id INTEGER NOT NULL PRIMARY KEY,
            title TEXT NOT NULL,
            description TEXT NOT NULL,
            category TEXT NOT NULL,
            brand TEXT,
            price REAL NOT NULL,
            discount_percentage REAL NOT NULL,
            discounted_price REAL NOT NULL,
            rating REAL NOT NULL,
            stock INTEGER NOT NULL,
            thumbnail TEXT NOT NULL,
            seen_generation INTEGER NOT NULL,
            deleted INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS product_tag (
            product_id INTEGER NOT NULL,
            position INTEGER NOT NULL,
            tag TEXT NOT NULL,
            PRIMARY KEY (product_id, position)
        )
        """,
    )

private const val UPSERT = """
INSERT OR REPLACE INTO product (
    id, title, description, category, brand, price, discount_percentage, discounted_price, rating, stock, thumbnail,
    seen_generation, deleted
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
"""

private const val INSERT_TAG = "INSERT INTO product_tag (product_id, position, tag) VALUES (?, ?, ?)"

private const val BY_ID = """
SELECT id, title, description, category, brand, price, discount_percentage, rating, stock, thumbnail
FROM product WHERE id = ? AND deleted = 0
"""

// Product.sq's pageAfter with its named parameters numbered: ?1 cursor, ?2 sort, ?3 text, ?4 category,
// ?5 minPrice, ?6 maxPrice, ?7 limit.
private const val PAGE_AFTER = """
WITH keyed AS (
  SELECT *, CASE ?2
      WHEN 'price-asc' THEN discounted_price
      WHEN 'price-desc' THEN -discounted_price
      WHEN 'rating-desc' THEN -rating
      ELSE id
    END AS sort_key
  FROM product
),
after AS (
  SELECT coalesce((SELECT sort_key FROM keyed WHERE id = ?1), CASE ?2 WHEN 'id' THEN ?1 END) AS cursor_key
)
SELECT id, title, price, discounted_price, rating, thumbnail FROM keyed
WHERE (?1 IS NULL OR sort_key > (SELECT cursor_key FROM after)
    OR (sort_key = (SELECT cursor_key FROM after) AND id > ?1))
  AND deleted = 0
  AND (instr(lower(title), lower(?3)) > 0 OR instr(lower(brand), lower(?3)) > 0)
  AND (?4 IS NULL OR category = ?4)
  AND (?5 IS NULL OR discounted_price >= ?5)
  AND (?6 IS NULL OR discounted_price <= ?6)
ORDER BY sort_key, id
LIMIT ?7
"""
