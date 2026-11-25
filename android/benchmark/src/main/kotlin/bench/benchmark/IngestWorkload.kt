package bench.benchmark

import bench.core.Core
import bench.core.IngestResult
import bench.fixtures.ProductFixtures

/** Benchmark 7: ingest one generated page of [count] products into a fresh database. */
class IngestWorkload(
    private val cores: FreshCores,
    count: Int,
    seed: Long,
) : AutoCloseable {
    private val body = ProductFixtures.page(count, seed)

    fun freshCore(): Core = cores.freshCore()

    fun ingestInto(core: Core): IngestResult = core.ingest(FIRST_REQUEST, HTTP_OK, body)

    override fun close() = cores.close()

    private companion object {
        const val FIRST_REQUEST = 1L
        const val HTTP_OK = 200
    }
}
