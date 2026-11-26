import Foundation
import Testing

struct ContractRunnerTests {
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

        try ContractRunner.run(contractCase, against: EchoCore())
    }

    @Test func aStepWhoseResultDiffersFailsNamingTheCaseFileTheStepAndBothValues() throws {
        let contractCase = try ContractCase(name: "mismatch", json: """
            {"steps": [
              {"step": "page", "args": {"size": 1}, "expect": {"step": "page", "args": {"size": 1}}},
              {"step": "page", "args": {"size": 2}, "expect": {"step": "page", "args": {"size": 3}}}
            ]}
            """)

        let failure = #expect(throws: ContractFailure.self) {
            try ContractRunner.run(contractCase, against: EchoCore())
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
            try ContractRunner.run(contractCase, against: EchoCore())
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
            try ContractRunner.run(contractCase, against: EchoCore())
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
}
