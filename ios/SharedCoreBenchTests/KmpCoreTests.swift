import Foundation
import SharedCore
import Testing

struct KmpCoreTests {
    @Test func aNewCoreReportsNoProducts() throws {
        let dbPath = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(UUID().uuidString).db").path
        defer { try? FileManager.default.removeItem(atPath: dbPath) }
        let config = CoreConfig(dbPath: dbPath, baseUrl: "https://example.test/products", pageSize: 20)
        let core = Core.companion.create(config: config, drivers: NativeDrivers())

        guard case let .stats(result) = onEnum(of: core.stats()) else {
            Issue.record("stats() failed: \(core.stats())")
            return
        }
        #expect(result.stats.productCount == 0)
    }
}
