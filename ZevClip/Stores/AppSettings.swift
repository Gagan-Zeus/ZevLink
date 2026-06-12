import Foundation
import ServiceManagement

@MainActor
final class AppSettings: ObservableObject {
    private enum DefaultsKey {
        static let migratedFromLegacyBundleId = "migratedFromLegacyBundleId"
        static let launchAtLoginEnabled = "launchAtLoginEnabled"
        static let launchAtLoginRegisteredPath = "launchAtLoginRegisteredPath"
        static let showMenuBarIcon = "showMenuBarIcon"
    }

    @Published private(set) var launchAtLoginEnabled: Bool
    @Published private(set) var launchAtLoginStatus: String
    @Published private(set) var showMenuBarIcon: Bool

    init() {
        Self.migrateLegacyDefaultsIfNeeded()
        let status = SMAppService.mainApp.status
        let savedPreference = UserDefaults.standard.object(
            forKey: DefaultsKey.launchAtLoginEnabled
        ) as? Bool
        let registeredPath = UserDefaults.standard.string(
            forKey: DefaultsKey.launchAtLoginRegisteredPath
        )
        let installedPathIsRegistered = savedPreference == true &&
            registeredPath == Bundle.main.bundleURL.path &&
            (Self.isLaunchAtLoginRequested(status) || Self.fallbackRegistrationExists)

        launchAtLoginEnabled = savedPreference ?? Self.isLaunchAtLoginRequested(status)
        launchAtLoginStatus = installedPathIsRegistered
            ? "Launch at Login is enabled."
            : Self.statusMessage(for: status)
        showMenuBarIcon = UserDefaults.standard.object(
            forKey: DefaultsKey.showMenuBarIcon
        ) as? Bool ?? true

        if launchAtLoginEnabled && !installedPathIsRegistered {
            Task { @MainActor [weak self] in
                self?.refreshInstalledAppRegistration()
            }
        }
    }

    func setShowMenuBarIcon(_ isVisible: Bool) {
        UserDefaults.standard.set(isVisible, forKey: DefaultsKey.showMenuBarIcon)
        showMenuBarIcon = isVisible
    }

    func setLaunchAtLoginEnabled(_ isEnabled: Bool) {
        UserDefaults.standard.set(isEnabled, forKey: DefaultsKey.launchAtLoginEnabled)
        launchAtLoginEnabled = isEnabled

        do {
            if isEnabled {
                try registerLaunchAtLogin()
            } else {
                unregisterLaunchAtLogin()
            }
            refreshLaunchAtLoginStatus()
        } catch {
            let status = SMAppService.mainApp.status
            launchAtLoginEnabled = Self.isLaunchAtLoginRequested(status)
            UserDefaults.standard.set(launchAtLoginEnabled, forKey: DefaultsKey.launchAtLoginEnabled)
            launchAtLoginStatus = "Could not update Launch at Login: \(error.localizedDescription)"
        }
    }

    func refreshLaunchAtLoginStatus() {
        let status = SMAppService.mainApp.status
        launchAtLoginEnabled = Self.isLaunchAtLoginRequested(status) ||
            Self.fallbackRegistrationExists
        UserDefaults.standard.set(launchAtLoginEnabled, forKey: DefaultsKey.launchAtLoginEnabled)
        launchAtLoginStatus = Self.fallbackRegistrationExists
            ? "Launch at Login is enabled."
            : Self.statusMessage(for: status)
    }

    private func refreshInstalledAppRegistration() {
        guard Bundle.main.bundleURL.path.hasPrefix("/Applications/") else {
            return
        }

        do {
            if SMAppService.mainApp.status != .notRegistered {
                try? SMAppService.mainApp.unregister()
            }
            try registerLaunchAtLogin()
            refreshLaunchAtLoginStatus()
        } catch {
            refreshLaunchAtLoginStatus()
            launchAtLoginStatus = "Could not refresh Launch at Login: \(error.localizedDescription)"
        }
    }

    private func registerLaunchAtLogin() throws {
        do {
            try SMAppService.mainApp.register()
            Self.removeFallbackRegistration()
        } catch {
            try Self.installFallbackRegistration()
        }

        UserDefaults.standard.set(
            Bundle.main.bundleURL.path,
            forKey: DefaultsKey.launchAtLoginRegisteredPath
        )
        UserDefaults.standard.set(true, forKey: DefaultsKey.launchAtLoginEnabled)
        launchAtLoginEnabled = true
    }

    private func unregisterLaunchAtLogin() {
        if SMAppService.mainApp.status != .notRegistered {
            try? SMAppService.mainApp.unregister()
        }
        Self.removeFallbackRegistration()
        UserDefaults.standard.removeObject(forKey: DefaultsKey.launchAtLoginRegisteredPath)
        UserDefaults.standard.set(false, forKey: DefaultsKey.launchAtLoginEnabled)
        launchAtLoginEnabled = false
    }

    private static var fallbackRegistrationURL: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/LaunchAgents", isDirectory: true)
            .appendingPathComponent("com.zevlink.receiver.plist")
    }

    private static var legacyFallbackRegistrationURL: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/LaunchAgents", isDirectory: true)
            .appendingPathComponent("com.zevclip.receiver.plist")
    }

    private static var fallbackRegistrationExists: Bool {
        FileManager.default.fileExists(atPath: fallbackRegistrationURL.path)
    }

    private static func installFallbackRegistration() throws {
        let propertyList: [String: Any] = [
            "Label": "com.zevlink.receiver",
            "ProgramArguments": [
                "/usr/bin/open",
                "-gj",
                Bundle.main.bundleURL.path
            ],
            "RunAtLoad": true
        ]
        let data = try PropertyListSerialization.data(
            fromPropertyList: propertyList,
            format: .xml,
            options: 0
        )
        let directory = fallbackRegistrationURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        try data.write(to: fallbackRegistrationURL, options: .atomic)
    }

    private static func removeFallbackRegistration() {
        try? FileManager.default.removeItem(at: fallbackRegistrationURL)
        try? FileManager.default.removeItem(at: legacyFallbackRegistrationURL)
    }

    private static func migrateLegacyDefaultsIfNeeded() {
        let defaults = UserDefaults.standard
        guard defaults.object(forKey: DefaultsKey.migratedFromLegacyBundleId) == nil else {
            return
        }

        if let legacyDomain = defaults.persistentDomain(forName: "com.zevclip.receiver") {
            for key in [
                DefaultsKey.launchAtLoginEnabled,
                DefaultsKey.launchAtLoginRegisteredPath,
                DefaultsKey.showMenuBarIcon
            ] where defaults.object(forKey: key) == nil {
                defaults.set(legacyDomain[key], forKey: key)
            }
        }

        try? FileManager.default.removeItem(at: legacyFallbackRegistrationURL)
        defaults.set(true, forKey: DefaultsKey.migratedFromLegacyBundleId)
    }

    private static func isLaunchAtLoginRequested(_ status: SMAppService.Status) -> Bool {
        status == .enabled || status == .requiresApproval
    }

    private static func statusMessage(for status: SMAppService.Status) -> String {
        switch status {
        case .enabled:
            return "Launch at Login is enabled."
        case .notRegistered:
            return "Launch at Login is off."
        case .requiresApproval:
            return "Launch at Login needs approval in System Settings."
        case .notFound:
            return "Launch at Login is unavailable for this build."
        @unknown default:
            return "Launch at Login status is unknown."
        }
    }
}
