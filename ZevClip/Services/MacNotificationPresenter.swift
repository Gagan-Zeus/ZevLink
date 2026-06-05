import Foundation
import UserNotifications

struct AndroidMirroredNotification: Decodable {
    let appName: String
    let packageName: String
    let title: String?
    let body: String?
    let subtext: String?
    let notificationKey: String?
    let postedAtMillis: Int64?

    var displayTitle: String {
        let trimmedTitle = title?.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedTitle?.isEmpty == false ? "\(appName): \(trimmedTitle!)" : appName
    }

    var displayBody: String {
        let candidates = [body, subtext]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return candidates.first ?? "New notification"
    }
}

final class MacNotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    static let shared = MacNotificationPresenter()

    private let center = UNUserNotificationCenter.current()
    private var authorizationRequested = false

    private override init() {
        super.init()
        center.delegate = self
    }

    func requestAuthorizationIfNeeded() {
        guard !authorizationRequested else { return }
        authorizationRequested = true

        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func show(_ notification: AndroidMirroredNotification) {
        requestAuthorizationIfNeeded()

        let content = UNMutableNotificationContent()
        content.title = notification.displayTitle
        content.body = notification.displayBody
        content.sound = .default
        content.userInfo = [
            "packageName": notification.packageName,
            "notificationKey": notification.notificationKey ?? ""
        ]

        let identifier = [
            "android",
            notification.packageName,
            notification.notificationKey ?? UUID().uuidString
        ].joined(separator: ".")

        center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}
