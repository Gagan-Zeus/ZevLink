import Foundation
import ServiceManagement

@MainActor
final class AppSettings: ObservableObject {
    private enum DefaultsKey {
        static let launchAtLoginEnabled = "launchAtLoginEnabled"
    }

    @Published private(set) var launchAtLoginEnabled: Bool
    @Published private(set) var launchAtLoginStatus: String

    init() {
        let status = SMAppService.mainApp.status
        let savedPreference = UserDefaults.standard.object(
            forKey: DefaultsKey.launchAtLoginEnabled
        ) as? Bool

        launchAtLoginEnabled = savedPreference ?? Self.isLaunchAtLoginRequested(status)
        launchAtLoginStatus = Self.statusMessage(for: status)
    }

    func setLaunchAtLoginEnabled(_ isEnabled: Bool) {
        UserDefaults.standard.set(isEnabled, forKey: DefaultsKey.launchAtLoginEnabled)
        launchAtLoginEnabled = isEnabled

        do {
            if isEnabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
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
        launchAtLoginEnabled = Self.isLaunchAtLoginRequested(status)
        UserDefaults.standard.set(launchAtLoginEnabled, forKey: DefaultsKey.launchAtLoginEnabled)
        launchAtLoginStatus = Self.statusMessage(for: status)
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
