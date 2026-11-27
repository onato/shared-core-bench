import Foundation
import SQLite3
import Testing
@testable import SharedCoreBench

/// Breaks the core's database behind its back, the way a full disk or a corrupt file would.
final class KmpSharedCoreTests {
    private let dbPath = FileManager.default.temporaryDirectory.appending(path: "\(UUID().uuidString).db").path()

    deinit {
        try? FileManager.default.removeItem(atPath: dbPath)
    }

    @Test func aStorageFailureInTheCoreReachesTheAppAsAFailedIngest() throws {
        let core = openedCore()
        try execute("CREATE TRIGGER fail_insert BEFORE INSERT ON product BEGIN SELECT RAISE(ABORT, 'disk full'); END")

        let body = """
            {"products": [{"id": 1, "title": "Mascara", "description": "Lash mascara", "category": "beauty",
              "brand": "Essence", "price": 9.99, "discountPercentage": 7.5, "rating": 4.5, "stock": 5,
              "thumbnail": "https://cdn.dummyjson.com/1.png", "tags": ["beauty"]}]}
            """
        let result = core.ingest(requestId: 1, status: 200, body: Data(body.utf8))

        #expect(result == .failed(.storageFailed))
    }

    @Test func aStorageFailureInTheCoreReachesTheAppAsAFailedPage() throws {
        let core = openedCore()
        try execute("DROP TABLE product")

        #expect(core.page(cursor: nil, size: 20, query: Query()) == .storageFailed)
    }

    @Test func aStorageFailureInTheCoreReachesTheAppAsAFailedDetail() throws {
        let core = openedCore()
        try execute("DROP TABLE product_tag")

        #expect(core.detail(id: 1) == .storageFailed)
    }

    @Test func aStorageFailureInTheCoreReachesTheAppAsFailedCategories() throws {
        let core = openedCore()
        try execute("DROP TABLE product")

        #expect(core.categories() == .storageFailed)
    }

    @Test func aStorageFailureInTheCoreReachesTheAppAsFailedStats() throws {
        let core = openedCore()
        try execute("DROP TABLE product")

        #expect(core.stats() == .storageFailed)
    }

    /// The native driver creates the schema on first use, so this reads once before the test breaks it.
    private func openedCore() -> KmpSharedCore {
        let core = KmpSharedCore.create(dbPath: dbPath)
        _ = core.stats()
        return core
    }

    private func execute(_ sql: String) throws {
        var database: OpaquePointer?
        defer { sqlite3_close(database) }
        let opened = sqlite3_open(dbPath, &database)
        guard opened == SQLITE_OK else { throw SQLiteError(code: opened) }
        let executed = sqlite3_exec(database, sql, nil, nil, nil)
        guard executed == SQLITE_OK else { throw SQLiteError(code: executed) }
    }
}

private struct SQLiteError: Error {
    let code: Int32
}
