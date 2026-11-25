package bench.benchmark

import bench.android.core.SqliteStoragePort
import bench.core.Core
import bench.core.CoreConfig
import bench.core.PortCalls
import java.io.File

/** Cores on the app's [SqliteStoragePort] at [path], making [calls] to it. */
class PortCores(
    private val path: String,
    private val calls: PortCalls,
) : FreshCores {
    // Core has no close(), so this keeps the port to stop connections piling up across iterations.
    private var port: SqliteStoragePort? = null

    override fun freshCore(): Core {
        close()
        SQLITE_FILES.forEach { File(path + it).delete() }
        return Core.create(CoreConfig(path), SqliteStoragePort.open(path).also { port = it }, calls)
    }

    override fun close() {
        port?.close()
        port = null
    }
}

// The database and the journal files SQLite keeps beside it.
private val SQLITE_FILES = listOf("", "-journal", "-wal", "-shm")
