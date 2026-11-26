import Foundation

struct ContractCase {
    let name: String
    let steps: [JSON]

    init(name: String, json: String) throws {
        try self.init(name: name, data: Data(json.utf8))
    }

    init(name: String, data: Data) throws {
        guard case let .array(steps) = try JSON(parsing: data)["steps"] else {
            throw ContractFailure(message: "\(name).json has no steps array")
        }
        self.name = name
        self.steps = steps
    }
}

extension ContractCase {
    /// Every case in `contract/cases`, which the test target bundles as its `cases` folder, by name.
    static func bundled() throws -> [ContractCase] {
        let dir = Bundle(for: BundleToken.self).bundleURL.appending(path: "cases")
        return try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
            .map { file in
                try ContractCase(
                    name: file.deletingPathExtension().lastPathComponent,
                    data: try Data(contentsOf: file))
            }
    }

    private final class BundleToken {}
}

struct ContractFailure: Error, CustomStringConvertible {
    let message: String
    var description: String { message }
}

enum ContractRunner {
    static func run(_ contractCase: ContractCase, against core: ContractCore) throws {
        for (index, step) in contractCase.steps.enumerated() {
            let type = if case let .string(type) = step["step"] { type } else { "" }
            let args = if case let .object(args) = step["args"] { args } else { [String: JSON]() }
            guard let actual = call(core, type, args) else {
                throw ContractFailure(message: "\(contractCase.name).json step \(index): unknown step type \"\(type)\"")
            }
            let expected = step["expect"] ?? .null
            if actual != expected {
                throw ContractFailure(message: """
                    \(contractCase.name).json step \(index) (\(type)) returned a different result
                    expected:
                    \(expected.pretty())
                    actual:
                    \(actual.pretty())
                    """)
            }
        }
    }

    private static func call(_ core: ContractCore, _ type: String, _ args: [String: JSON]) -> JSON? {
        switch type {
        case "ingest": core.ingest(args)
        case "page": core.page(args)
        case "nextFetch": core.nextFetch(args)
        case "detail": core.detail(args)
        case "stats": core.stats(args)
        case "refresh": core.refresh(args)
        case "categories": core.categories(args)
        default: nil
        }
    }
}
