package bench.core

import bench.contract.ContractRunner
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

class KmpContractTest {
    @TestFactory
    fun contractCases(): List<DynamicTest> {
        val dir = File(System.getProperty("contract.cases"))
        check(dir.isDirectory) { "no contract cases at $dir" }
        return ContractRunner.cases(dir) { KmpContractCore(Core.create(CoreConfig(dbPath = ":memory:"))) }
    }
}
