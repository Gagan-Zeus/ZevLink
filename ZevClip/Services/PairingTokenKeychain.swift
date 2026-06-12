import Foundation
import Security

enum PairingTokenKeychain {
    private static let service = "com.zevlink.receiver"
    private static let legacyService = "com.zevclip.receiver"
    private static let account = "pairing-token"
    private static let byteCount = 32

    static func loadOrCreateToken() throws -> String {
        if let token = try loadToken(), !token.isEmpty {
            return token
        }

        if let legacyToken = try loadToken(service: legacyService), !legacyToken.isEmpty {
            try saveToken(legacyToken)
            return legacyToken
        }

        let token = try generateToken()
        try saveToken(token)
        return token
    }

    static func regenerateToken() throws -> String {
        let token = try generateToken()
        try saveToken(token)
        return token
    }

    private static func loadToken() throws -> String? {
        try loadToken(service: service)
    }

    private static func loadToken(service: String) throws -> String? {
        var query = baseQuery(service: service)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        if status == errSecItemNotFound {
            return nil
        }

        guard status == errSecSuccess else {
            throw PairingTokenError.keychainReadFailed(status)
        }

        guard
            let data = result as? Data,
            let token = String(data: data, encoding: .utf8)
        else {
            throw PairingTokenError.invalidStoredToken
        }

        return token
    }

    private static func saveToken(_ token: String) throws {
        let data = Data(token.utf8)
        let query = baseQuery(service: service)

        let attributes: [String: Any] = [
            kSecValueData as String: data
        ]

        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess {
            return
        }

        guard updateStatus == errSecItemNotFound else {
            throw PairingTokenError.keychainWriteFailed(updateStatus)
        }

        var addQuery = query
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw PairingTokenError.keychainWriteFailed(addStatus)
        }
    }

    private static func generateToken() throws -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)

        guard status == errSecSuccess else {
            throw PairingTokenError.randomGenerationFailed(status)
        }

        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    private static func baseQuery(service: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }
}

enum PairingTokenError: LocalizedError {
    case invalidStoredToken
    case keychainReadFailed(OSStatus)
    case keychainWriteFailed(OSStatus)
    case randomGenerationFailed(OSStatus)

    var errorDescription: String? {
        switch self {
        case .invalidStoredToken:
            return "The pairing token stored in Keychain is invalid."
        case .keychainReadFailed(let status):
            return "Could not read pairing token from Keychain (status \(status))."
        case .keychainWriteFailed(let status):
            return "Could not save pairing token to Keychain (status \(status))."
        case .randomGenerationFailed(let status):
            return "Could not generate a random pairing token (status \(status))."
        }
    }
}

final class PairingTokenProvider {
    private let lock = NSLock()
    private var token: String

    init(token: String) {
        self.token = token
    }

    func currentToken() -> String {
        lock.withLock { token }
    }

    func updateToken(_ token: String) {
        lock.withLock {
            self.token = token
        }
    }
}
