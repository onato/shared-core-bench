import Foundation

/// A JSON value as the contract compares it: `10` and `10.0` are different values.
enum JSON: Equatable, Sendable {
    case null
    case bool(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case array([JSON])
    case object([String: JSON])

    init(parsing data: Data) throws {
        self.init(try JSONSerialization.jsonObject(with: data, options: .fragmentsAllowed))
    }

    private init(_ value: Any) {
        switch value {
        case let number as NSNumber where CFGetTypeID(number) == CFBooleanGetTypeID():
            self = .bool(number.boolValue)
        case let number as NSNumber where CFNumberIsFloatType(number):
            self = .double(number.doubleValue)
        case let number as NSNumber:
            self = .int(number.intValue)
        case let string as String:
            self = .string(string)
        case let array as [Any]:
            self = .array(array.map(JSON.init))
        case let object as [String: Any]:
            self = .object(object.mapValues(JSON.init))
        default:
            self = .null
        }
    }

    /// The field named `key` when this is an object that has it.
    subscript(key: String) -> JSON? {
        if case let .object(fields) = self { fields[key] } else { nil }
    }

    /// The value as indented JSON with sorted keys, writing each number as the case would.
    func pretty(indent: String = "") -> String {
        let inner = indent + "    "
        switch self {
        case .null: return "null"
        case let .bool(value): return String(value)
        case let .int(value): return String(value)
        case let .double(value): return String(value)
        case let .string(value): return Self.quoted(value)
        case let .array(items):
            if items.isEmpty { return "[]" }
            let lines = items.map { inner + $0.pretty(indent: inner) }
            return "[\n" + lines.joined(separator: ",\n") + "\n" + indent + "]"
        case let .object(fields):
            if fields.isEmpty { return "{}" }
            let lines = fields.keys.sorted().map { key in
                inner + Self.quoted(key) + ": " + (fields[key] ?? .null).pretty(indent: inner)
            }
            return "{\n" + lines.joined(separator: ",\n") + "\n" + indent + "}"
        }
    }

    private static func quoted(_ text: String) -> String {
        let options: JSONSerialization.WritingOptions = [.fragmentsAllowed, .withoutEscapingSlashes]
        let data = (try? JSONSerialization.data(withJSONObject: text, options: options)) ?? Data()
        return String(bytes: data, encoding: .utf8) ?? ""
    }
}
