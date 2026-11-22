package bench.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreTest {
    @Test
    fun createdCoreReportsTheConfigItWasCreatedWith() {
        val config = CoreConfig(dbPath = "products.db")

        val core = Core.create(config)

        assertEquals(CoreConfig(dbPath = "products.db"), core.config)
    }
}
