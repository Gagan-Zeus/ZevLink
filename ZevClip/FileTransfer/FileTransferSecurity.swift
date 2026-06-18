import CryptoKit
import Foundation
import Network
import Security

struct FileTransferDeviceIdentity {
    let deviceId: String
    let privateKey: SecKey
    let certificate: SecCertificate
    let secIdentity: SecIdentity
    let certificateDER: Data
    let certificateSHA256: String
}

enum FileTransferSecurityError: Error, LocalizedError {
    case keychainReadFailed(OSStatus)
    case keychainWriteFailed(OSStatus)
    case keyGenerationFailed
    case publicKeyExportFailed
    case certificateGenerationFailed
    case identityCreationFailed(OSStatus)
    case randomGenerationFailed(OSStatus)
    case invalidPinnedFingerprint
    case authenticationFailed

    var errorDescription: String? {
        switch self {
        case .keychainReadFailed(let status):
            return "Could not read file-transfer identity from Keychain (status \(status))."
        case .keychainWriteFailed(let status):
            return "Could not save file-transfer identity to Keychain (status \(status))."
        case .keyGenerationFailed:
            return "Could not generate a file-transfer private key."
        case .publicKeyExportFailed:
            return "Could not export the file-transfer public key."
        case .certificateGenerationFailed:
            return "Could not generate the file-transfer certificate."
        case .identityCreationFailed(let status):
            return "Could not create the file-transfer SecIdentity (status \(status))."
        case .randomGenerationFailed(let status):
            return "Could not generate secure random bytes (status \(status))."
        case .invalidPinnedFingerprint:
            return "Pinned transfer certificate fingerprint is invalid."
        case .authenticationFailed:
            return "Transfer authentication failed."
        }
    }
}

enum FileTransferIdentityStore {
    private static let keyTag = Data("com.zevlink.transfer.identity.v1".utf8)
    private static let certificateService = "com.zevlink.receiver.transfer"
    private static let certificateAccount = "identity-certificate-der"
    private static let certificateLabel = "ZevLink Transfer Identity"

    static func loadOrCreate(deviceId: String) throws -> FileTransferDeviceIdentity {
        let privateKey = try loadPrivateKey() ?? createPrivateKey()
        let certificateDER = try loadCertificateDER() ?? createAndSaveCertificateDER(
            privateKey: privateKey,
            deviceId: deviceId
        )

        guard let certificate = SecCertificateCreateWithData(nil, certificateDER as CFData) else {
            try deleteCertificateDER()
            let regeneratedDER = try createAndSaveCertificateDER(privateKey: privateKey, deviceId: deviceId)
            guard let regeneratedCertificate = SecCertificateCreateWithData(nil, regeneratedDER as CFData) else {
                throw FileTransferSecurityError.certificateGenerationFailed
            }
            return try identity(
                deviceId: deviceId,
                privateKey: privateKey,
                certificate: regeneratedCertificate,
                certificateDER: regeneratedDER
            )
        }

        return try identity(
            deviceId: deviceId,
            privateKey: privateKey,
            certificate: certificate,
            certificateDER: certificateDER
        )
    }

    private static func identity(
        deviceId: String,
        privateKey: SecKey,
        certificate: SecCertificate,
        certificateDER: Data
    ) throws -> FileTransferDeviceIdentity {
        try addCertificateToKeychain(certificate)

        var secIdentity: SecIdentity?
        let status = SecIdentityCreateWithCertificate(nil, certificate, &secIdentity)
        guard status == errSecSuccess, let secIdentity else {
            throw FileTransferSecurityError.identityCreationFailed(status)
        }

        return FileTransferDeviceIdentity(
            deviceId: deviceId,
            privateKey: privateKey,
            certificate: certificate,
            secIdentity: secIdentity,
            certificateDER: certificateDER,
            certificateSHA256: FileTransferCertificates.sha256Hex(certificateDER)
        )
    }

    private static func loadPrivateKey() throws -> SecKey? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: keyTag,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess else {
            throw FileTransferSecurityError.keychainReadFailed(status)
        }
        return (result as! SecKey)
    }

    private static func createPrivateKey() throws -> SecKey {
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: keyTag,
                kSecAttrLabel as String: "ZevLink Transfer Private Key"
            ]
        ]

        var error: Unmanaged<CFError>?
        guard let privateKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw FileTransferSecurityError.keyGenerationFailed
        }
        return privateKey
    }

    private static func loadCertificateDER() throws -> Data? {
        var query = certificateQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess, let data = result as? Data else {
            throw FileTransferSecurityError.keychainReadFailed(status)
        }
        return data
    }

    private static func createAndSaveCertificateDER(privateKey: SecKey, deviceId: String) throws -> Data {
        let certificateDER = try SelfSignedCertificateBuilder.makeCertificateDER(
            privateKey: privateKey,
            commonName: "ZevLink Mac Transfer \(deviceId)"
        )
        try saveCertificateDER(certificateDER)
        return certificateDER
    }

    private static func saveCertificateDER(_ certificateDER: Data) throws {
        let query = certificateQuery()
        let attributes: [String: Any] = [
            kSecValueData as String: certificateDER
        ]

        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess {
            return
        }
        guard updateStatus == errSecItemNotFound else {
            throw FileTransferSecurityError.keychainWriteFailed(updateStatus)
        }

        var addQuery = query
        addQuery[kSecValueData as String] = certificateDER
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw FileTransferSecurityError.keychainWriteFailed(addStatus)
        }
    }

    private static func deleteCertificateDER() throws {
        let status = SecItemDelete(certificateQuery() as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw FileTransferSecurityError.keychainWriteFailed(status)
        }
    }

    private static func addCertificateToKeychain(_ certificate: SecCertificate) throws {
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecAttrLabel as String: certificateLabel
        ]
        SecItemDelete(deleteQuery as CFDictionary)

        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecAttrLabel as String: certificateLabel,
            kSecValueRef as String: certificate
        ]
        let status = SecItemAdd(addQuery as CFDictionary, nil)
        guard status == errSecSuccess || status == errSecDuplicateItem else {
            throw FileTransferSecurityError.keychainWriteFailed(status)
        }
    }

    private static func certificateQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: certificateService,
            kSecAttrAccount as String: certificateAccount
        ]
    }
}

enum FileTransferPeerPinStore {
    private static let service = "com.zevlink.receiver.transfer.peer-pin"

    static func pinnedCertificateSHA256(peerDeviceId: String) throws -> String? {
        let normalizedPeer = peerDeviceId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !normalizedPeer.isEmpty else { return nil }

        var query = baseQuery(peerDeviceId: normalizedPeer)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess,
              let data = result as? Data,
              let fingerprint = String(data: data, encoding: .utf8)?.normalizedTransferFingerprint
        else {
            throw FileTransferSecurityError.keychainReadFailed(status)
        }
        return fingerprint
    }

    static func savePinnedCertificateSHA256(_ fingerprint: String?, peerDeviceId: String) throws {
        let normalizedPeer = peerDeviceId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !normalizedPeer.isEmpty else { return }

        var query = baseQuery(peerDeviceId: normalizedPeer)
        guard let fingerprint else {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw FileTransferSecurityError.keychainWriteFailed(status)
            }
            return
        }

        guard let normalizedFingerprint = fingerprint.normalizedTransferFingerprint else {
            throw FileTransferSecurityError.invalidPinnedFingerprint
        }

        let data = Data(normalizedFingerprint.utf8)
        let attributes: [String: Any] = [
            kSecValueData as String: data
        ]

        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess {
            return
        }
        guard updateStatus == errSecItemNotFound else {
            throw FileTransferSecurityError.keychainWriteFailed(updateStatus)
        }

        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let addStatus = SecItemAdd(query as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw FileTransferSecurityError.keychainWriteFailed(addStatus)
        }
    }

    private static func baseQuery(peerDeviceId: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: peerDeviceId
        ]
    }
}

enum FileTransferCertificates {
    static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func isValidFingerprint(_ value: String?) -> Bool {
        value?.normalizedTransferFingerprint != nil
    }
}

enum FileTransferTLS {
    static func parameters(
        identity: FileTransferDeviceIdentity,
        pinnedPeerCertificateSHA256: String
    ) throws -> NWParameters {
        let tlsOptions = try options(
            identity: identity,
            pinnedPeerCertificateSHA256: pinnedPeerCertificateSHA256
        )
        return NWParameters(tls: tlsOptions, tcp: .init())
    }

    static func options(
        identity: FileTransferDeviceIdentity,
        pinnedPeerCertificateSHA256: String
    ) throws -> NWProtocolTLS.Options {
        guard let pinnedFingerprint = pinnedPeerCertificateSHA256.normalizedTransferFingerprint else {
            throw FileTransferSecurityError.invalidPinnedFingerprint
        }

        let options = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(options.securityProtocolOptions, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(options.securityProtocolOptions, .TLSv13)
        sec_protocol_options_set_local_identity(
            options.securityProtocolOptions,
            sec_identity_create(identity.secIdentity)!
        )
        sec_protocol_options_set_verify_block(
            options.securityProtocolOptions,
            { metadata, _, complete in
                let isPinned = peerCertificateFingerprint(from: metadata) == pinnedFingerprint
                complete(isPinned)
            },
            DispatchQueue.global(qos: .userInitiated)
        )
        return options
    }

    private static func peerCertificateFingerprint(from metadata: sec_protocol_metadata_t) -> String? {
        var fingerprint: String?
        let accessible = sec_protocol_metadata_access_peer_certificate_chain(metadata) { certificate in
            guard fingerprint == nil else { return }
            let secCertificate = sec_certificate_copy_ref(certificate).takeRetainedValue()
            let data = SecCertificateCopyData(secCertificate) as Data
            fingerprint = FileTransferCertificates.sha256Hex(data)
        }
        return accessible ? fingerprint : nil
    }
}

struct FileTransferAuthHeaders {
    let protocolVersion: String
    let deviceId: String
    let timestamp: String
    let nonce: String
    let bodySHA256: String
    let authorization: String

    var dictionary: [String: String] {
        [
            "ZevLink-Protocol-Version": protocolVersion,
            "ZevLink-Device-ID": deviceId,
            "ZevLink-Transfer-Timestamp": timestamp,
            "ZevLink-Transfer-Nonce": nonce,
            "ZevLink-Body-SHA256": bodySHA256,
            "ZevLink-Transfer-Authorization": authorization
        ]
    }
}

enum FileTransferAuthenticator {
    private static let authScheme = "hmac-sha256"

    static func headers(
        pairingToken: String,
        deviceId: String,
        method: String,
        path: String,
        body: Data,
        now: Date = Date(),
        nonce: Data? = nil
    ) throws -> FileTransferAuthHeaders {
        let normalizedToken = pairingToken.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedDeviceId = deviceId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedToken.isEmpty, !normalizedDeviceId.isEmpty, !method.isEmpty, path.hasPrefix("/") else {
            throw FileTransferSecurityError.authenticationFailed
        }

        let nonceData = try nonce ?? randomData(byteCount: 16)
        guard nonceData.count >= 16 else {
            throw FileTransferSecurityError.authenticationFailed
        }

        let protocolVersion = String(ZevLinkTransferProtocol.version)
        let timestamp = String(Int(now.timeIntervalSince1970))
        let nonceHex = nonceData.hexString
        let bodySHA256 = FileTransferCertificates.sha256Hex(body)
        let canonical = canonicalString(
            method: method,
            path: path,
            protocolVersion: protocolVersion,
            deviceId: normalizedDeviceId,
            timestamp: timestamp,
            nonce: nonceHex,
            bodySHA256: bodySHA256
        )
        let signature = hmacSHA256(token: normalizedToken, canonical: canonical)

        return FileTransferAuthHeaders(
            protocolVersion: protocolVersion,
            deviceId: normalizedDeviceId,
            timestamp: timestamp,
            nonce: nonceHex,
            bodySHA256: bodySHA256,
            authorization: "\(authScheme)=\(signature)"
        )
    }

    static func verify(
        pairingToken: String,
        method: String,
        path: String,
        headers: [String: String]
    ) -> Bool {
        guard
            let protocolVersion = headers.caseInsensitiveValue("ZevLink-Protocol-Version"),
            protocolVersion == String(ZevLinkTransferProtocol.version),
            let deviceId = headers.caseInsensitiveValue("ZevLink-Device-ID"),
            let timestamp = headers.caseInsensitiveValue("ZevLink-Transfer-Timestamp"),
            let nonce = headers.caseInsensitiveValue("ZevLink-Transfer-Nonce"),
            let bodySHA256 = headers.caseInsensitiveValue("ZevLink-Body-SHA256"),
            let authorization = headers.caseInsensitiveValue("ZevLink-Transfer-Authorization")
        else {
            return false
        }

        let canonical = canonicalString(
            method: method,
            path: path,
            protocolVersion: protocolVersion,
            deviceId: deviceId,
            timestamp: timestamp,
            nonce: nonce,
            bodySHA256: bodySHA256
        )
        let expectedAuthorization = "\(authScheme)=\(hmacSHA256(token: pairingToken, canonical: canonical))"
        return Data(expectedAuthorization.utf8).constantTimeEquals(Data(authorization.utf8))
    }

    private static func canonicalString(
        method: String,
        path: String,
        protocolVersion: String,
        deviceId: String,
        timestamp: String,
        nonce: String,
        bodySHA256: String
    ) -> String {
        [
            method.uppercased(),
            path,
            protocolVersion,
            deviceId,
            timestamp,
            nonce,
            bodySHA256
        ].joined(separator: "\n")
    }

    private static func hmacSHA256(token: String, canonical: String) -> String {
        let key = SymmetricKey(data: Data(token.utf8))
        return HMAC<SHA256>
            .authenticationCode(for: Data(canonical.utf8), using: key)
            .map { String(format: "%02x", $0) }
            .joined()
    }

    private static func randomData(byteCount: Int) throws -> Data {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        guard status == errSecSuccess else {
            throw FileTransferSecurityError.randomGenerationFailed(status)
        }
        return Data(bytes)
    }
}

private enum SelfSignedCertificateBuilder {
    static func makeCertificateDER(privateKey: SecKey, commonName: String) throws -> Data {
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else {
            throw FileTransferSecurityError.publicKeyExportFailed
        }

        var publicKeyError: Unmanaged<CFError>?
        guard let publicKeyData = SecKeyCopyExternalRepresentation(publicKey, &publicKeyError) as Data? else {
            throw FileTransferSecurityError.publicKeyExportFailed
        }

        let algorithm = derSequence([
            derOID([1, 2, 840, 10045, 4, 3, 2])
        ])
        let name = distinguishedName(commonName: commonName)
        let validity = derSequence([
            derUTCTime(Date(timeIntervalSinceNow: -3600)),
            derUTCTime(Date(timeIntervalSinceNow: 20 * 365 * 24 * 60 * 60))
        ])
        let subjectPublicKeyInfo = derSequence([
            derSequence([
                derOID([1, 2, 840, 10045, 2, 1]),
                derOID([1, 2, 840, 10045, 3, 1, 7])
            ]),
            derBitString(publicKeyData)
        ])

        let tbsCertificate = try derSequence([
            derTagged(0, derInteger(2)),
            derInteger(randomBytes(count: 16)),
            algorithm,
            name,
            validity,
            name,
            subjectPublicKeyInfo
        ])

        var signatureError: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(
            privateKey,
            .ecdsaSignatureMessageX962SHA256,
            tbsCertificate as CFData,
            &signatureError
        ) as Data? else {
            throw FileTransferSecurityError.certificateGenerationFailed
        }

        let certificate = derSequence([
            tbsCertificate,
            algorithm,
            derBitString(signature)
        ])
        guard SecCertificateCreateWithData(nil, certificate as CFData) != nil else {
            throw FileTransferSecurityError.certificateGenerationFailed
        }
        return certificate
    }

    private static func distinguishedName(commonName: String) -> Data {
        derSequence([
            derSet([
                derSequence([
                    derOID([2, 5, 4, 3]),
                    derUTF8String(commonName)
                ])
            ])
        ])
    }

    private static func randomBytes(count: Int) throws -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        guard status == errSecSuccess else {
            throw FileTransferSecurityError.randomGenerationFailed(status)
        }
        bytes[0] &= 0x7f
        if bytes.allSatisfy({ $0 == 0 }) {
            bytes[0] = 1
        }
        return Data(bytes)
    }

    private static func derSequence(_ values: [Data]) -> Data {
        der(tag: 0x30, content: values.reduce(Data(), +))
    }

    private static func derSet(_ values: [Data]) -> Data {
        der(tag: 0x31, content: values.reduce(Data(), +))
    }

    private static func derTagged(_ index: UInt8, _ content: Data) -> Data {
        der(tag: 0xa0 + index, content: content)
    }

    private static func derInteger(_ value: Int) -> Data {
        derInteger(Data([UInt8(value)]))
    }

    private static func derInteger(_ value: Data) -> Data {
        var bytes = Array(value)
        while bytes.count > 1 && bytes.first == 0 && (bytes[1] & 0x80) == 0 {
            bytes.removeFirst()
        }
        if let first = bytes.first, (first & 0x80) != 0 {
            bytes.insert(0, at: 0)
        }
        return der(tag: 0x02, content: Data(bytes))
    }

    private static func derOID(_ components: [Int]) -> Data {
        precondition(components.count >= 2)
        var bytes = [UInt8(components[0] * 40 + components[1])]
        components.dropFirst(2).forEach { component in
            var value = component
            var encoded = [UInt8(value & 0x7f)]
            value >>= 7
            while value > 0 {
                encoded.insert(UInt8(value & 0x7f) | 0x80, at: 0)
                value >>= 7
            }
            bytes.append(contentsOf: encoded)
        }
        return der(tag: 0x06, content: Data(bytes))
    }

    private static func derUTF8String(_ value: String) -> Data {
        der(tag: 0x0c, content: Data(value.utf8))
    }

    private static func derUTCTime(_ date: Date) -> Data {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyMMddHHmmss'Z'"
        return der(tag: 0x17, content: Data(formatter.string(from: date).utf8))
    }

    private static func derBitString(_ value: Data) -> Data {
        var content = Data([0x00])
        content.append(value)
        return der(tag: 0x03, content: content)
    }

    private static func der(tag: UInt8, content: Data) -> Data {
        var data = Data([tag])
        data.append(derLength(content.count))
        data.append(content)
        return data
    }

    private static func derLength(_ length: Int) -> Data {
        if length < 128 {
            return Data([UInt8(length)])
        }

        var value = length
        var bytes: [UInt8] = []
        while value > 0 {
            bytes.insert(UInt8(value & 0xff), at: 0)
            value >>= 8
        }
        return Data([0x80 | UInt8(bytes.count)] + bytes)
    }
}

private extension String {
    var normalizedTransferFingerprint: String? {
        let normalized = trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: ":", with: "")
            .replacingOccurrences(of: " ", with: "")
        guard normalized.count == 64,
              normalized.allSatisfy({ ("0"..."9").contains($0) || ("a"..."f").contains($0) })
        else {
            return nil
        }
        return normalized
    }
}

private extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }

    func constantTimeEquals(_ other: Data) -> Bool {
        guard count == other.count else { return false }
        var difference: UInt8 = 0
        for index in indices {
            difference |= self[index] ^ other[index]
        }
        return difference == 0
    }
}

private extension Dictionary where Key == String, Value == String {
    func caseInsensitiveValue(_ key: String) -> String? {
        first { $0.key.caseInsensitiveCompare(key) == .orderedSame }?.value
    }
}
