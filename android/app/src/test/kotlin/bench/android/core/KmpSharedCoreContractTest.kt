package bench.android.core

import bench.contract.ContractRunner
import bench.core.Core
import bench.core.CoreConfig
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

class KmpSharedCoreContractTest {
    @TestFactory
    fun contractCases(): List<DynamicTest> {
        val dir = File(checkNotNull(System.getProperty("contract.cases")) { "contract.cases is not set" })
        check(dir.isDirectory) { "no contract cases at $dir" }
        return ContractRunner.cases(dir) {
            SharedCoreContract(KmpSharedCore(Core.create(CoreConfig(dbPath = ":memory:"), JvmDrivers)))
        }
    }
}
