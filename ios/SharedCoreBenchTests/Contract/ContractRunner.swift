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

}

enum ContractShapes {
    /// `detail`'s field names from `contract/shapes.json`, which the test target bundles.
    static func bundledDetailFields() throws -> Set<String> {
        let file = Bundle(for: BundleToken.self).bundleURL.appending(path: "shapes.json")
        return try JSONDecoder().decode([String: Set<String>].self, from: Data(contentsOf: file))["detail"] ?? []
    }
}

private final class BundleToken {}

struct ContractFailure: Error, CustomStringConvertible {
    let message: String
    var description: String { message }
}

enum ContractRunner {
    static func run(_ contractCase: ContractCase, against core: ContractCore, detailFields: Set<String>) throws {
        for (index, step) in contractCase.steps.enumerated() {
            let type = if case let .string(type) = step["step"] { type } else { "" }
            let args = if case let .object(args) = step["args"] { args } else { [String: JSON]() }
            guard let actual = call(core, type, args) else {
                throw ContractFailure(message: "\(contractCase.name).json step \(index): unknown step type \"\(type)\"")
            }
            let location = "\(contractCase.name).json step \(index) (\(type))"
            if type == "detail", let product = product(actual) {
                try checkShape(location, detailFields, product)
            }
            let expected = step["expect"] ?? .null
            let compared = compared(type, expected, actual)
            if compared != expected {
                throw ContractFailure(message: """
                    \(location) returned a different result
                    expected:
                    \(expected.pretty())
                    actual:
                    \(compared.pretty())
                    """)
            }
        }
    }

    /// A `detail` product is compared on the fields `expect` lists; every other result whole.
    private static func compared(_ type: String, _ expected: JSON, _ actual: JSON) -> JSON {
        guard type == "detail", let listed = product(expected), case let .object(fields) = actual else {
            return actual
        }
        return .object(fields.filter { listed[$0.key] != nil })
    }

    private static func checkShape(_ location: String, _ fields: Set<String>, _ product: [String: JSON]) throws {
        let missing = fields.subtracting(product.keys)
        if !missing.isEmpty {
            throw ContractFailure(
                message: "\(location) returned a product without the fields shapes.json lists: \(listed(missing))")
        }
        let extra = Set(product.keys).subtracting(fields)
        if !extra.isEmpty {
            throw ContractFailure(
                message: "\(location) returned a product with fields shapes.json does not list: \(listed(extra))")
        }
    }

    private static func listed(_ fields: Set<String>) -> String {
        "[" + fields.sorted().joined(separator: ", ") + "]"
    }

    /// A `detail` result other than `null` and `{"error": …}`.
    private static func product(_ result: JSON) -> [String: JSON]? {
        guard case let .object(fields) = result, fields["error"] == nil else { return nil }
        return fields
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
