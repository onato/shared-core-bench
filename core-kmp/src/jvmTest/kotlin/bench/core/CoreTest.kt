package bench.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreTest {
    @Test
    fun createdCoreReportsTheConfigItWasCreatedWith() {
        val config = CoreConfig(dbPath = ":memory:")

        val core = Core.create(config, JdbcDrivers)

        assertEquals(CoreConfig(dbPath = ":memory:"), core.config)
    }
}
