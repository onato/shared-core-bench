import Foundation
import Testing

struct ContractRunnerTests {
    /// `EchoCore`'s `detail` echoes its step and args, so that echo is the product shape.
    private let echoShape: Set = ["step", "args"]

    @Test func aCaseWhoseEveryStepMatchesPasses() throws {
        let contractCase = try ContractCase(name: "all-steps", json: """
            {"steps": [
              {"step": "ingest", "args": {"requestId": 1}, "expect": {"step": "ingest", "args": {"requestId": 1}}},
              {"step": "page", "args": {"size": 20}, "expect": {"step": "page", "args": {"size": 20}}},
              {"step": "nextFetch", "expect": {"step": "nextFetch", "args": {}}},
              {"step": "detail", "args": {"id": 1}, "expect": {"step": "detail", "args": {"id": 1}}},
              {"step": "stats", "expect": {"step": "stats", "args": {}}},
              {"step": "refresh", "expect": {"step": "refresh", "args": {}}},
              {"step": "categories", "expect": {"step": "categories", "args": {}}}
            ]}
            """)

        try ContractRunner.run(contractCase, against: EchoCore(), detailFields: echoShape)
    }

    @Test func aStepWhoseResultDiffersFailsNamingTheCaseFileTheStepAndBothValues() throws {
        let contractCase = try ContractCase(name: "mismatch", json: """
            {"steps": [
              {"step": "page", "args": {"size": 1}, "expect": {"step": "page", "args": {"size": 1}}},
              {"step": "page", "args": {"size": 2}, "expect": {"step": "page", "args": {"size": 3}}}
            ]}
            """)

        let failure = #expect(throws: ContractFailure.self) {
            try ContractRunner.run(contractCase, against: EchoCore(), detailFields: echoShape)
        }

        #expect(failure?.message == """
            mismatch.json step 1 (page) returned a different result
            expected:
            {
                "args": {
                    "size": 3
                },
                "step": "page"
            }
            actual:
            {
                "args": {
                    "size": 2
                },
                "step": "page"
            }
            """)
    }

    @Test func aWholeNumberWrittenWithoutAFractionDiffersFromOneWrittenWithIt() throws {
        let contractCase = try ContractCase(name: "price", json: """
            {"steps": [{"step": "stats", "args": {"price": 10}, "expect": {"step": "stats", "args": {"price": 10.0}}}]}
            """)

        let failure = #expect(throws: ContractFailure.self) {
            try ContractRunner.run(contractCase, against: EchoCore(), detailFields: echoShape)
        }

        #expect(failure?.message == """
            price.json step 0 (stats) returned a different result
            expected:
            {
                "args": {
                    "price": 10.0
                },
                "step": "stats"
            }
            actual:
            {
                "args": {
                    "price": 10
                },
                "step": "stats"
            }
            """)
    }

    @Test func anUnknownStepTypeFailsNamingTheCaseFileAndTheStep() throws {
        let contractCase = try ContractCase(name: "typo", json: """
            {"steps": [
              {"step": "page", "expect": {"step": "page", "args": {}}},
              {"step": "detial", "expect": null}
            ]}
            """)

        let failure = #expect(throws: ContractFailure.self) {
            try ContractRunner.run(contractCase, against: EchoCore(), detailFields: echoShape)
        }

        #expect(failure?.message == "typo.json step 1: unknown step type \"detial\"")
    }

    @Test func everyFileInTheContractCasesDirectoryIsLoaded() throws {
        let casesDir = URL(filePath: #filePath)
            .deletingLastPathComponent()
            .appending(path: "../../../contract/cases")
        let files = try FileManager.default.contentsOfDirectory(atPath: casesDir.path())
            .filter { $0.hasSuffix(".json") }
            .map { String($0.dropLast(".json".count)) }
            .sorted()

        let loaded = try ContractCase.bundled().map(\.name)

        #expect(files.count > 0)
        #expect(loaded == files)
    }

    @Test func theDetailFieldsComeFromTheBundledShapesFile() throws {
        let fields = try ContractShapes.bundledDetailFields()

        #expect(fields.count == 14)
        #expect(fields.isSuperset(of: ["id", "discountedPrice", "ratingBucket", "stockStatus", "tags"]))
    }

    @Test func aDetailStepPassesWhenTheFieldsExpectListsMatchThoughAnotherFieldDiffers() throws {
        let contractCase = try ContractCase(name: "unlisted-field-differs", json: """
            {"steps": [
              {"step": "detail", "args": {"returns": {"id": 1, "title": "Mug", "stock": 7}},
               "expect": {"id": 1, "title": "Mug"}}
            ]}
            """)

        try ContractRunner.run(contractCase, against: ReturningCore(), detailFields: ["id", "title", "stock"])
    }

    @Test func aDetailStepFailsWhenAFieldExpectListsDiffersShowingOnlyTheListedFields() throws {
        let contractCase = try ContractCase(name: "listed-field-differs", json: """
            {"steps": [
              {"step": "detail", "args": {"returns": {"id": 1, "title": "Mug", "stock": 7}},
               "expect": {"id": 1, "title": "Cup"}}
            ]}
            """)

        let failure = #expect(throws: ContractFailure.self) {
            try ContractRunner.run(contractCase, against: ReturningCore(), detailFields: ["id", "title", "stock"])
        }

        #expect(failure?.message == """
            listed-field-differs.json step 0 (detail) returned a different result
            expected:
            {
                "id": 1,
                "title": "Cup"
            }
            actual:
            {
                "id": 1,
                "title": "Mug"
            }
            """)
    }

    @Test func aDetailProductMissingAFieldShapesListsFailsThoughExpectDoesNotListIt() throws {
        let contractCase = try ContractCase(name: "missing-field", json: """
            {"steps": [{"step": "detail", "args": {"returns": {"id": 1, "stock": 7}}, "expect": {"id": 1}}]}
            """)

        let failure = #expect(throws: ContractFailure.self) {
            try ContractRunner.run(contractCase, against: ReturningCore(), detailFields: ["id", "title", "stock"])
        }

        #expect(failure?.message ==
            "missing-field.json step 0 (detail) returned a product without the fields shapes.json lists: [title]")
    }

    @Test func aDetailProductWithAFieldShapesDoesNotListFails() throws {
        let contractCase = try ContractCase(name: "extra-field", json: """
            {"steps": [
              {"step": "detail", "args": {"returns": {"id": 1, "stock": 7, "colour": "red"}}, "expect": {"id": 1}}
            ]}
            """)

        let failure = #expect(throws: ContractFailure.self) {
            try ContractRunner.run(contractCase, against: ReturningCore(), detailFields: ["id", "stock"])
        }

        #expect(failure?.message ==
            "extra-field.json step 0 (detail) returned a product with fields shapes.json does not list: [colour]")
    }

    @Test func aDetailStepExpectingNullOrAnErrorIsComparedWhole() throws {
        let contractCase = try ContractCase(name: "error", json: """
            {"steps": [
              {"step": "detail", "args": {"returns": null}, "expect": null},
              {"step": "detail", "args": {"returns": {"error": "storage-failed"}},
               "expect": {"error": "storage-failed"}},
              {"step": "detail", "args": {"returns": {"error": "storage-failed", "status": 500}},
               "expect": {"error": "storage-failed"}}
            ]}
            """)

        let failure = #expect(throws: ContractFailure.self) {
            try ContractRunner.run(contractCase, against: ReturningCore(), detailFields: ["id", "stock"])
        }

        #expect(failure?.message == """
            error.json step 2 (detail) returned a different result
            expected:
            {
                "error": "storage-failed"
            }
            actual:
            {
                "error": "storage-failed",
                "status": 500
            }
            """)
    }
}

/// Its `detail` returns the step's `returns` argument as the product.
private struct ReturningCore: ContractCore {
    func ingest(_ args: [String: JSON]) -> JSON { .null }
    func page(_ args: [String: JSON]) -> JSON { .null }
    func nextFetch(_ args: [String: JSON]) -> JSON { .null }
    func detail(_ args: [String: JSON]) -> JSON { args["returns"] ?? .null }
    func stats(_ args: [String: JSON]) -> JSON { .null }
    func refresh(_ args: [String: JSON]) -> JSON { .null }
    func categories(_ args: [String: JSON]) -> JSON { .null }
}
