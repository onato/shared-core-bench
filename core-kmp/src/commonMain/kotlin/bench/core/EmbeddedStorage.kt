package bench.core

import bench.core.db.ProductDatabase

internal class EmbeddedStorage(
    database: ProductDatabase,
) : StoragePort {
    private val queries = database.productQueries

    override fun transaction(block: () -> Unit) = queries.transaction { block() }

    override fun upsert(
        items: List<ProductDetail>,
        generation: Long,
    ) = items.forEach { upsertOne(it, generation) }

    override fun upsertOne(
        item: ProductDetail,
        generation: Long,
    ) = with(item) {
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

    override fun markMissingDeleted(generation: Long) {
        queries.softDeleteUnseenBefore(generation)
    }

    override fun query(
        query: Query,
        cursor: Long?,
        size: Int,
    ): List<ProductSummary> =
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
            }.executeAsList()

    override fun get(id: Long): ProductDetail? {
        val tags = queries.tagsOf(id).executeAsList()
        return queries
            .byId(id) { id, title, description, category, brand, price, discount, rating, stock, thumbnail ->
                ProductDetail(id, title, description, category, brand, price, discount, rating, stock, thumbnail, tags)
            }.executeAsOneOrNull()
    }

    override fun categories(): List<String> = queries.categories().executeAsList()

    override fun stats(): CoreStats =
        with(queries) {
            CoreStats(productCount = count().executeAsOne(), deletedCount = deletedCount().executeAsOne())
        }
}
