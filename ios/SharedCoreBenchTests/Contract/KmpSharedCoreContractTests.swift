import Foundation
import Testing
@testable import SharedCoreBench

struct KmpSharedCoreContractTests {
    @Test(arguments: try ContractCase.bundled())
    func contractCase(_ contractCase: ContractCase) throws {
        let dbPath = FileManager.default.temporaryDirectory.appending(path: "\(UUID().uuidString).db").path()
        defer { try? FileManager.default.removeItem(atPath: dbPath) }

        try ContractRunner.run(
            contractCase,
            against: SharedCoreContract(core: KmpSharedCore.create(dbPath: dbPath)),
            detailFields: ContractShapes.bundledDetailFields())
    }
}

extension ContractCase: CustomTestStringConvertible {
    var testDescription: String { name }
}
