package bench.core

import bench.core.db.ProductDatabase

/** A storage port over the embedded schema and SQL, standing in for the app's port on the JVM. */
fun jdbcTestPort(dbPath: String): StoragePort = EmbeddedStorage(ProductDatabase(JdbcDrivers.open(dbPath)))
