import Foundation

enum AndroidReceiverIdentityStore {
    private static let pairedDeviceIdKey = "zevclip.androidReceiver.pairedDeviceId"

    static func pairedDeviceId() -> String? {
        UserDefaults.standard.string(forKey: pairedDeviceIdKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .nilIfEmpty
    }

    static func savePairedDeviceId(_ deviceId: String?) {
        guard let normalizedDeviceId = deviceId?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .nilIfEmpty
        else {
            return
        }

        UserDefaults.standard.set(normalizedDeviceId, forKey: pairedDeviceIdKey)
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
