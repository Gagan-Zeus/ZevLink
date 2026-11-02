import Foundation

enum IntentExtraType: String, CaseIterable, Codable, Identifiable {
    case string
    case boolean
    case integer
    case long
    case float
    case double
    case stringArray

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .string: return "String"
        case .boolean: return "Boolean"
        case .integer: return "Integer"
        case .long: return "Long"
        case .float: return "Float"
        case .double: return "Double"
        case .stringArray: return "String array"
        }
    }
}

struct IntentExtra: Codable, Equatable, Identifiable {
    var id: UUID
    var key: String
    var type: IntentExtraType
    var value: String

    init(
        id: UUID = UUID(),
        key: String = "",
        type: IntentExtraType = .string,
        value: String = ""
    ) {
        self.id = id
        self.key = key
        self.type = type
        self.value = value
    }
}

enum IntentExtraWireValue: Codable, Equatable {
    case string(String)
    case boolean(Bool)
    case integer(Int32)
    case long(Int64)
    case float(Float)
    case double(Double)
    case stringArray([String])

    private enum CodingKeys: String, CodingKey {
        case type
        case value
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(IntentExtraType.self, forKey: .type)
        switch type {
        case .string:
            self = .string(try container.decode(String.self, forKey: .value))
        case .boolean:
            self = .boolean(try container.decode(Bool.self, forKey: .value))
        case .integer:
            self = .integer(try container.decode(Int32.self, forKey: .value))
        case .long:
            self = .long(try container.decode(Int64.self, forKey: .value))
        case .float:
            self = .float(try container.decode(Float.self, forKey: .value))
        case .double:
            self = .double(try container.decode(Double.self, forKey: .value))
        case .stringArray:
            self = .stringArray(try container.decode([String].self, forKey: .value))
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .string(let value):
            try container.encode(IntentExtraType.string, forKey: .type)
            try container.encode(value, forKey: .value)
        case .boolean(let value):
            try container.encode(IntentExtraType.boolean, forKey: .type)
            try container.encode(value, forKey: .value)
        case .integer(let value):
            try container.encode(IntentExtraType.integer, forKey: .type)
            try container.encode(value, forKey: .value)
        case .long(let value):
            try container.encode(IntentExtraType.long, forKey: .type)
            try container.encode(value, forKey: .value)
        case .float(let value):
            try container.encode(IntentExtraType.float, forKey: .type)
            try container.encode(value, forKey: .value)
        case .double(let value):
            try container.encode(IntentExtraType.double, forKey: .type)
            try container.encode(value, forKey: .value)
        case .stringArray(let value):
            try container.encode(IntentExtraType.stringArray, forKey: .type)
            try container.encode(value, forKey: .value)
        }
    }
}

struct IntentBroadcastExtra: Codable, Equatable {
    let type: IntentExtraType
    let value: IntentExtraWireValue

    init(type: IntentExtraType, value: IntentExtraWireValue) {
        self.type = type
        self.value = value
    }
}

struct IntentBroadcastRequest: Codable, Equatable {
    let type: String
    let version: Int
    let requestId: String
    let action: String
    let targetPackage: String?
    let dataUri: String?
    let mimeType: String?
    let categories: [String]
    let flags: Int64?
    let extras: [String: IntentBroadcastExtra]

    init(
        requestId: String,
        action: String,
        targetPackage: String?,
        dataUri: String?,
        mimeType: String?,
        categories: [String],
        flags: Int64?,
        extras: [String: IntentBroadcastExtra]
    ) {
        self.type = "intent_broadcast"
        self.version = 1
        self.requestId = requestId
        self.action = action
        self.targetPackage = targetPackage
        self.dataUri = dataUri
        self.mimeType = mimeType
        self.categories = categories
        self.flags = flags
        self.extras = extras
    }
}

struct IntentBroadcastAcknowledgement: Codable, Equatable {
    let type: String
    let version: Int
    let requestId: String
    let status: String
    let success: Bool
    let message: String

    init(requestId: String, status: String, success: Bool, message: String) {
        self.type = "intent_broadcast_ack"
        self.version = 1
        self.requestId = requestId
        self.status = status
        self.success = success
        self.message = message
    }
}

enum IntentBroadcastDeliveryStatus: Equatable {
    case idle
    case notConnected
    case sending
    case requestDelivered
    case intentBroadcastRequested
    case broadcastOperationFailed
    case requestTimedOut
    case invalidConfiguration

    var title: String {
        switch self {
        case .idle: return "Ready"
        case .notConnected: return "Not connected"
        case .sending: return "Sending"
        case .requestDelivered: return "Request delivered to Android"
        case .intentBroadcastRequested: return "Intent broadcast requested"
        case .broadcastOperationFailed: return "Broadcast operation failed"
        case .requestTimedOut: return "Request timed out"
        case .invalidConfiguration: return "Invalid configuration"
        }
    }
}

struct IntentPreset: Codable, Equatable, Identifiable {
    var id: UUID
    var name: String
    var action: String
    var targetPackage: String
    var dataUri: String
    var mimeType: String
    var categories: String
    var flags: String
    var extras: [IntentExtra]

    init(
        id: UUID = UUID(),
        name: String = "New Intent",
        action: String = "",
        targetPackage: String = "",
        dataUri: String = "",
        mimeType: String = "",
        categories: String = "",
        flags: String = "",
        extras: [IntentExtra] = []
    ) {
        self.id = id
        self.name = name
        self.action = action
        self.targetPackage = targetPackage
        self.dataUri = dataUri
        self.mimeType = mimeType
        self.categories = categories
        self.flags = flags
        self.extras = extras
    }

    func makeRequest(requestId: String = UUID().uuidString) throws -> IntentBroadcastRequest {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty, trimmedName.count <= 120 else {
            throw IntentConfigurationError.invalidField("Intent name is required and must be at most 120 characters.")
        }

        guard action == action.trimmingCharacters(in: .whitespacesAndNewlines),
              !action.isEmpty,
              action.count <= 256,
              !action.containsControlCharacter
        else {
            throw IntentConfigurationError.invalidField("Action is required, must be exact, and must be at most 256 characters.")
        }

        let targetPackage = try optionalPackage(targetPackage)
        let dataUri = try optionalURI(dataUri)
        let mimeType = try optionalMIMEType(mimeType)
        let categories = try parseCategories(categories)
        let flags = try parseFlags(flags)

        guard extras.count <= 64 else {
            throw IntentConfigurationError.invalidField("An Intent may contain at most 64 extras.")
        }

        var wireExtras: [String: IntentBroadcastExtra] = [:]
        for extra in extras {
            let key = extra.key
            guard key == key.trimmingCharacters(in: .whitespacesAndNewlines),
                  !key.isEmpty,
                  key.count <= 128,
                  !key.containsControlCharacter
            else {
                throw IntentConfigurationError.invalidField("Every extra key is required and must be at most 128 characters.")
            }
            guard wireExtras[key] == nil else {
                throw IntentConfigurationError.invalidField("Extra keys must be unique.")
            }
            wireExtras[key] = try parseExtra(extra)
        }

        return IntentBroadcastRequest(
            requestId: requestId,
            action: action,
            targetPackage: targetPackage,
            dataUri: dataUri,
            mimeType: mimeType,
            categories: categories,
            flags: flags,
            extras: wireExtras
        )
    }

    private func optionalPackage(_ value: String) throws -> String? {
        guard !value.isEmpty else { return nil }
        guard value.count <= 255,
              value.range(of: #"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)*$"#, options: .regularExpression) != nil
        else {
            throw IntentConfigurationError.invalidField("Target package must be a valid Android package name.")
        }
        return value
    }

    private func optionalURI(_ value: String) throws -> String? {
        guard !value.isEmpty else { return nil }
        guard value.count <= 4096, URL(string: value) != nil else {
            throw IntentConfigurationError.invalidField("Data URI is not valid.")
        }
        return value
    }

    private func optionalMIMEType(_ value: String) throws -> String? {
        guard !value.isEmpty else { return nil }
        guard value.count <= 255,
              value.range(of: #"^[^\s/]+/[^\s/]+$"#, options: .regularExpression) != nil
        else {
            throw IntentConfigurationError.invalidField("MIME type must look like type/subtype.")
        }
        return value
    }

    private func parseCategories(_ value: String) throws -> [String] {
        let result = value
            .split(whereSeparator: { $0.isNewline })
            .map(String.init)
        guard result.count <= 32,
              result.allSatisfy({ !$0.isEmpty && $0.count <= 128 && !$0.containsControlCharacter }),
              Set(result).count == result.count
        else {
            throw IntentConfigurationError.invalidField("Categories must be unique, one per line, and at most 32 entries.")
        }
        return result
    }

    private func parseFlags(_ value: String) throws -> Int64? {
        guard !value.isEmpty else { return nil }
        let normalized = value.lowercased().hasPrefix("0x") ? String(value.dropFirst(2)) : value
        guard let parsed = Int64(normalized, radix: value.lowercased().hasPrefix("0x") ? 16 : 10), parsed >= 0 else {
            throw IntentConfigurationError.invalidField("Flags must be a non-negative decimal or hexadecimal number.")
        }
        return parsed
    }

    private func parseExtra(_ extra: IntentExtra) throws -> IntentBroadcastExtra {
        let value = extra.value
        switch extra.type {
        case .string:
            guard value.utf8.count <= 8_192 else { throw IntentConfigurationError.invalidField("String extras must be at most 8 KiB.") }
            return IntentBroadcastExtra(type: .string, value: .string(value))
        case .boolean:
            guard let parsed = Bool(value.lowercased()) else { throw IntentConfigurationError.invalidField("Boolean extra \(extra.key) must be true or false.") }
            return IntentBroadcastExtra(type: .boolean, value: .boolean(parsed))
        case .integer:
            guard let parsed = Int32(value) else { throw IntentConfigurationError.invalidField("Integer extra \(extra.key) is invalid.") }
            return IntentBroadcastExtra(type: .integer, value: .integer(parsed))
        case .long:
            guard let parsed = Int64(value) else { throw IntentConfigurationError.invalidField("Long extra \(extra.key) is invalid.") }
            return IntentBroadcastExtra(type: .long, value: .long(parsed))
        case .float:
            guard let parsed = Float(value), parsed.isFinite else { throw IntentConfigurationError.invalidField("Float extra \(extra.key) is invalid.") }
            return IntentBroadcastExtra(type: .float, value: .float(parsed))
        case .double:
            guard let parsed = Double(value), parsed.isFinite else { throw IntentConfigurationError.invalidField("Double extra \(extra.key) is invalid.") }
            return IntentBroadcastExtra(type: .double, value: .double(parsed))
        case .stringArray:
            let values = value.split(whereSeparator: { $0.isNewline }).map(String.init)
            guard values.count <= 128, values.allSatisfy({ $0.utf8.count <= 8_192 }) else {
                throw IntentConfigurationError.invalidField("String arrays may contain at most 128 values, each at most 8 KiB.")
            }
            return IntentBroadcastExtra(type: .stringArray, value: .stringArray(values))
        }
    }
}

private extension String {
    var containsControlCharacter: Bool {
        unicodeScalars.contains { $0.value < 32 || $0.value == 127 }
    }
}

enum IntentConfigurationError: LocalizedError, Equatable {
    case invalidField(String)

    var errorDescription: String? {
        switch self {
        case .invalidField(let message): return message
        }
    }
}
