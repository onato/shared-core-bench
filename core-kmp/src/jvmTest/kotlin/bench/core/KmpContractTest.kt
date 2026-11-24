package bench.core

import bench.contract.ContractRunner
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

class KmpContractTest {
    private val config = CoreConfig(dbPath = ":memory:")

    @TestFactory
    fun contractCases(): List<DynamicTest> = cases { Core.create(config, JdbcDrivers) }

    @TestFactory
    fun contractCasesOnTheStoragePort(): List<DynamicTest> = cases { Core.create(config, jdbcTestPort(config.dbPath)) }

    @TestFactory
    fun contractCasesOnTheChattyStoragePort(): List<DynamicTest> =
        cases { Core.create(config, jdbcTestPort(config.dbPath), PortCalls.CHATTY) }

    private fun cases(core: () -> Core): List<DynamicTest> {
        val dir = File(System.getProperty("contract.cases"))
        check(dir.isDirectory) { "no contract cases at $dir" }
        return ContractRunner.cases(dir) { KmpContractCore(core()) }
    }
}
