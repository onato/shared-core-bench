package bench.benchmark

import bench.core.Core

/** Where a benchmark's core keeps its products. */
interface FreshCores : AutoCloseable {
    /** A core on an empty database, closing the one opened before it. */
    fun freshCore(): Core
}
