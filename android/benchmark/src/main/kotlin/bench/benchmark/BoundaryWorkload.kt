package bench.benchmark

import bench.core.Boundary
import bench.core.ProductSummary

/**
 * Benchmarks 1–5 at one [size]: the call count, string length in bytes, byte count or record count. Inputs are
 * built once here, so a measured call does nothing but cross into the core.
 */
class BoundaryWorkload(
    private val size: Int,
) {
    // ASCII, so one character is one UTF-8 byte.
    private val text = "x".repeat(size)
    private val bytes = ByteArray(size)

    fun noOpCalls(): Int {
        repeat(size) { Boundary.noOp() }
        return size
    }

    fun stringRoundTrip(): String = Boundary.echo(text)

    fun bytesIn(): Int = Boundary.bytesIn(bytes)

    fun records(): List<ProductSummary> = Boundary.records(size)

    fun callbacks(): Int {
        var calls = 0
        Boundary.callback(size) { calls++ }
        return calls
    }
}
