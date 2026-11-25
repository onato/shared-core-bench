package bench.android.core

import bench.contract.ContractRunner
import bench.core.Core
import bench.core.CoreConfig
import bench.core.PortCalls
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

class KmpSharedCoreContractTest {
    private val config = CoreConfig(dbPath = ":memory:")

    @TestFactory
    fun contractCases(): List<DynamicTest> = cases { Core.create(config, JvmDrivers) }

    @TestFactory
    fun contractCasesOnTheAndroidPort(): List<DynamicTest> =
        cases {
            Core.create(config, SqliteStoragePort.open(config.dbPath))
        }

    @TestFactory
    fun contractCasesOnTheChattyAndroidPort(): List<DynamicTest> =
        cases { Core.create(config, SqliteStoragePort.open(config.dbPath), PortCalls.CHATTY) }

    private fun cases(core: () -> Core): List<DynamicTest> {
        val dir = File(checkNotNull(System.getProperty("contract.cases")) { "contract.cases is not set" })
        check(dir.isDirectory) { "no contract cases at $dir" }
        return ContractRunner.cases(dir) { SharedCoreContract(KmpSharedCore(core())) }
    }
}
