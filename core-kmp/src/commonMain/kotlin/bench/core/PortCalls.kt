package bench.core

/** How the core hands an ingested page to a [StoragePort]. */
enum class PortCalls {
    /** One `upsert(items)` per page. */
    BATCHED,

    /** One `upsertOne(item)` per product. */
    CHATTY,
}
