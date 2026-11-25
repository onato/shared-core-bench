package bench.core

import bench.core.db.ProductDatabase

internal class EmbeddedStorage(
    database: ProductDatabase,
) : StoragePort {
    private val queries = database.productQueries

    override fun transaction(block: () -> StorageResult<Unit>): StorageResult<Unit> =
        catching {
            queries.transactionWithResult {
                block().also { if (it is StorageError) rollback(it) }
            }
        }

    override fun upsert(
        items: List<ProductDetail>,
        generation: Long,
    ): StorageResult<Unit> = items.storeEach { upsertOne(it, generation) }

    override fun upsertOne(
        item: ProductDetail,
        generation: Long,
    ): StorageResult<Unit> =
        catching {
            with(item) {
                queries.upsert(
                    id,
                    title,
                    description,
                    category,
                    brand,
                    price,
                    discountPercentage,
                    discountedPrice,
                    rating,
                    stock,
                    thumbnail,
                    generation,
                )
                queries.deleteTagsOf(id)
                tags.forEachIndexed { position, tag -> queries.insertTag(id, position.toLong(), tag) }
            }
            Stored(Unit)
        }

    override fun markMissingDeleted(generation: Long): StorageResult<Unit> =
        catching {
            queries.softDeleteUnseenBefore(generation)
            Stored(Unit)
        }

    override fun query(
        query: Query,
        cursor: Long?,
        size: Int,
    ): StorageResult<List<ProductSummary>> =
        catching {
            Stored(
                queries
                    .pageAfter(
                        cursor = cursor,
                        text = query.text,
                        category = query.category,
                        minPrice = query.minPrice,
                        maxPrice = query.maxPrice,
                        sort = query.sort.key,
                        limit = size.toLong(),
                    ) { id, title, price, discountedPrice, rating, thumbnail ->
                        ProductSummary(id, title, price, discountedPrice, rating, thumbnail)
                    }.executeAsList(),
            )
        }

    override fun get(id: Long): StorageResult<ProductDetail?> =
        catching {
            val tags = queries.tagsOf(id).executeAsList()
            Stored(
                queries
                    .byId(id) { id, title, description, category, brand, price, discount, rating, stock, thumbnail ->
                        ProductDetail(
                            id,
                            title,
                            description,
                            category,
                            brand,
                            price,
                            discount,
                            rating,
                            stock,
                            thumbnail,
                            tags,
                        )
                    }.executeAsOneOrNull(),
            )
        }

    override fun categories(): List<String> = queries.categories().executeAsList()

    override fun stats(): CoreStats =
        with(queries) {
            CoreStats(productCount = count().executeAsOne(), deletedCount = deletedCount().executeAsOne())
        }
}

// Drivers throw platform exceptions (java.sql.SQLException, android.database.SQLException)
// with no common supertype, so common code can only catch Exception.
@Suppress("TooGenericExceptionCaught")
private inline fun <T> catching(call: () -> StorageResult<T>): StorageResult<T> =
    try {
        call()
    } catch (failure: Exception) {
        StorageError(failure.toString())
    }
