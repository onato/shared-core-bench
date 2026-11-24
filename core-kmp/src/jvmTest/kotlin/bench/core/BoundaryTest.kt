package bench.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BoundaryTest {
    @Test
    fun noOpReturnsNothing() {
        assertEquals(Unit, Boundary.noOp())
    }

    @Test
    fun echoReturnsTheStringItWasGiven() {
        assertEquals("héllo", Boundary.echo("héllo"))
    }

    @Test
    fun bytesInCountsTheBytesItReceived() {
        assertEquals(5, Boundary.bytesIn(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun recordsReturnsCountSummariesNumberedFromOne() {
        assertEquals(
            listOf(
                ProductSummary(1, "Product 1", 10.0, 9.0, 4.5, "https://cdn.dummyjson.com/1.png"),
                ProductSummary(2, "Product 2", 10.0, 9.0, 4.5, "https://cdn.dummyjson.com/2.png"),
            ),
            Boundary.records(2),
        )
    }

    @Test
    fun callbackCallsBackOncePerIndexInOrder() {
        val received = mutableListOf<Int>()

        Boundary.callback(times = 3) { received += it }

        assertEquals(listOf(0, 1, 2), received)
    }
}
