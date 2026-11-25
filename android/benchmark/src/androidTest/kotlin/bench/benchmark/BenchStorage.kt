package bench.benchmark

import androidx.test.platform.app.InstrumentationRegistry
import bench.core.PortCalls
import java.io.File

/** The cores for the storage variant `make bench` passes as the `storage` instrumentation argument. */
fun benchCores(): FreshCores {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val portPath = File(context.filesDir, "port-benchmark.db").path
    return when (val storage = InstrumentationRegistry.getArguments().getString("storage", "embedded")) {
        "embedded" -> EmbeddedCores(context)
        "batched" -> PortCores(portPath, PortCalls.BATCHED)
        "chatty" -> PortCores(portPath, PortCalls.CHATTY)
        else -> error("unknown storage variant: $storage")
    }
}
