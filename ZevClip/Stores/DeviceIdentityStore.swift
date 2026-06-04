import Foundation

enum DeviceIdentityStore {
    private static let deviceIdKey = "zevclip.deviceId"

    static func loadOrCreateDeviceId() -> String {
        let defaults = UserDefaults.standard

        if let existing = defaults.string(forKey: deviceIdKey)?.trimmingCharacters(in: .whitespacesAndNewlines),
           !existing.isEmpty {
            return existing
        }

        let deviceId = UUID().uuidString.lowercased()
        defaults.set(deviceId, forKey: deviceIdKey)
        return deviceId
    }
}
