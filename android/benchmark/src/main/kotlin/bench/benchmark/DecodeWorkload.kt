package bench.benchmark

import bench.core.IngestResult
import bench.core.Workloads
import bench.fixtures.ProductFixtures

/** Benchmark 6: decode one generated page of [count] products, storing nothing. */
class DecodeWorkload(
    count: Int,
    seed: Long,
) {
    private val body = ProductFixtures.page(count, seed)

    fun decode(): IngestResult = Workloads.decode(body)
}
