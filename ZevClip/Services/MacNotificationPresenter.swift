import AppKit
import UserNotifications

struct AndroidMirroredNotification: Decodable {
    let event: String?
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

    var isRemoval: Bool {
        event == "removed"
    }

    var macNotificationIdentifier: String {
        [
            "android",
            packageName,
            notificationKey ?? "unknown"
        ].joined(separator: ".")
    }
}

struct AndroidMirroredCall: Decodable {
    let event: String
    let callId: String
    let title: String
    let body: String
    let callerName: String?
    let callerNumber: String?
    let direction: String?
    let timestampMillis: Int64?

    var isRinging: Bool {
        event == "ringing"
    }

    var isAnswered: Bool {
        event == "answered"
    }

    var isEnded: Bool {
        event == "ended"
    }

    var isIncoming: Bool {
        direction == nil || direction == "incoming"
    }

    var macNotificationIdentifier: String {
        "android.call.\(callId)"
    }

    var displayTitle: String {
        let candidates = [callerName, callerNumber, title]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return candidates.first ?? "Incoming Android call"
    }

    var displayBody: String {
        if
            let callerName,
            let callerNumber,
            !callerName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
            !callerNumber.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        {
            return callerNumber
        }

        let bodyText = body.trimmingCharacters(in: .whitespacesAndNewlines)
        return bodyText.isEmpty ? "Your phone is ringing." : bodyText
    }
}

final class MacNotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    static let shared = MacNotificationPresenter()

    var onDismiss: ((String) -> Void)?
    var onCallAction: ((String, String, @escaping (Bool, String) -> Void) -> Void)?

    private let center = UNUserNotificationCenter.current()
    private var authorizationRequested = false
    private var activeCallPanel: CallControlPanelController?

    private override init() {
        super.init()
        center.delegate = self
        center.setNotificationCategories([
            UNNotificationCategory(
                identifier: Self.androidNotificationCategory,
                actions: [],
                intentIdentifiers: [],
                options: [.customDismissAction]
            ),
            UNNotificationCategory(
                identifier: Self.androidCallCategory,
                actions: [
                    UNNotificationAction(
                        identifier: Self.callActionAccept,
                        title: "Accept",
                        options: []
                    ),
                    UNNotificationAction(
                        identifier: Self.callActionReject,
                        title: "Reject",
                        options: [.destructive]
                    ),
                    UNNotificationAction(
                        identifier: Self.callActionSilence,
                        title: "Silence",
                        options: []
                    )
                ],
                intentIdentifiers: [],
                options: []
            )
        ])
    }

    func requestAuthorizationIfNeeded() {
        guard !authorizationRequested else { return }
        authorizationRequested = true

        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func show(_ notification: AndroidMirroredNotification) {
        requestAuthorizationIfNeeded()

        if notification.isRemoval {
            remove(notification)
            return
        }

        let content = UNMutableNotificationContent()
        content.title = notification.displayTitle
        content.body = notification.displayBody
        content.sound = .default
        content.categoryIdentifier = Self.androidNotificationCategory
        content.userInfo = [
            "packageName": notification.packageName,
            "notificationKey": notification.notificationKey ?? ""
        ]

        center.add(
            UNNotificationRequest(
                identifier: notification.macNotificationIdentifier,
                content: content,
                trigger: nil
            )
        )
    }

    func show(_ call: AndroidMirroredCall) {
        requestAuthorizationIfNeeded()

        if call.isEnded {
            remove(call)
            return
        }

        guard call.isIncoming else { return }

        if call.isAnswered {
            center.removeDeliveredNotifications(withIdentifiers: [call.macNotificationIdentifier])
            center.removePendingNotificationRequests(withIdentifiers: [call.macNotificationIdentifier])
            DispatchQueue.main.async { [weak self] in
                self?.showCallPanel(call)
            }
            return
        }

        guard call.isRinging else { return }

        DispatchQueue.main.async { [weak self] in
            self?.showCallPanel(call)
        }
    }

    private func remove(_ notification: AndroidMirroredNotification) {
        let identifiers = [notification.macNotificationIdentifier]
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
    }

    private func remove(_ call: AndroidMirroredCall) {
        let identifiers = [call.macNotificationIdentifier]
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
        DispatchQueue.main.async { [weak self] in
            guard self?.activeCallPanel?.callId == call.callId else { return }
            self?.activeCallPanel?.close()
            self?.activeCallPanel = nil
        }
    }

    private func showCallPanel(_ call: AndroidMirroredCall) {
        if activeCallPanel?.callId == call.callId {
            activeCallPanel?.update(call)
            return
        }

        let panel = CallControlPanelController(call: call) { [weak self] action, callId, completion in
            self?.onCallAction?(action, callId, completion)
        }
        activeCallPanel = panel
        panel.show()
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let userInfo = response.notification.request.content.userInfo
        if response.notification.request.content.categoryIdentifier == Self.androidCallCategory {
            guard
                let callId = userInfo["callId"] as? String,
                !callId.isEmpty
            else {
                return
            }

            switch response.actionIdentifier {
            case Self.callActionAccept:
                onCallAction?("accept", callId, { _, _ in })
            case Self.callActionReject:
                onCallAction?("reject", callId, { _, _ in })
            case Self.callActionSilence:
                onCallAction?("silence", callId, { _, _ in })
            default:
                break
            }
            return
        }

        guard response.actionIdentifier == UNNotificationDismissActionIdentifier else {
            return
        }

        guard
            let notificationKey = userInfo["notificationKey"] as? String,
            !notificationKey.isEmpty
        else {
            return
        }

        onDismiss?(notificationKey)
    }

    private static let androidNotificationCategory = "ANDROID_MIRRORED_NOTIFICATION"
    private static let androidCallCategory = "ANDROID_MIRRORED_CALL"
    private static let callActionAccept = "ANDROID_CALL_ACCEPT"
    private static let callActionReject = "ANDROID_CALL_REJECT"
    private static let callActionSilence = "ANDROID_CALL_SILENCE"
}

private final class CallControlPanelController: NSObject {
    let callId: String

    private let panel: NSPanel
    private let titleLabel = NSTextField(labelWithString: "")
    private let bodyLabel = NSTextField(labelWithString: "")
    private let statusLabel = NSTextField(labelWithString: "")
    private let acceptButton = NSButton(title: "Accept", target: nil, action: nil)
    private let rejectButton = NSButton(title: "Reject", target: nil, action: nil)
    private let silenceButton = NSButton(title: "Silence", target: nil, action: nil)
    private let onAction: (String, String, @escaping (Bool, String) -> Void) -> Void
    private var activeSince: Date?
    private var timer: Timer?

    init(
        call: AndroidMirroredCall,
        onAction: @escaping (String, String, @escaping (Bool, String) -> Void) -> Void
    ) {
        callId = call.callId
        self.onAction = onAction

        panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 340, height: 136),
            styleMask: [.titled, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.title = "Android Call"
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false

        super.init()
        configureContent()
        update(call)
    }

    func show() {
        positionNearTopRight()
        panel.orderFrontRegardless()
    }

    func close() {
        stopTimer()
        panel.close()
    }

    func update(_ call: AndroidMirroredCall) {
        titleLabel.stringValue = call.displayTitle
        bodyLabel.stringValue = call.displayBody

        let isAnswered = call.event == "answered"
        acceptButton.isHidden = isAnswered
        silenceButton.isHidden = isAnswered
        rejectButton.title = isAnswered ? "End Call" : "Reject"

        if isAnswered {
            if activeSince == nil {
                activeSince = call.timestampMillis.map {
                    Date(timeIntervalSince1970: TimeInterval($0) / 1_000)
                } ?? Date()
            }
            startTimer()
            updateTimerText()
        } else {
            activeSince = nil
            stopTimer()
            statusLabel.stringValue = "Incoming Android call"
        }
    }

    private func configureContent() {
        guard let contentView = panel.contentView else { return }

        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 7
        stack.translatesAutoresizingMaskIntoConstraints = false

        titleLabel.font = .systemFont(ofSize: 17, weight: .semibold)
        titleLabel.lineBreakMode = .byTruncatingTail
        bodyLabel.font = .systemFont(ofSize: 13)
        bodyLabel.textColor = .secondaryLabelColor
        bodyLabel.lineBreakMode = .byTruncatingTail
        statusLabel.font = .systemFont(ofSize: 12)
        statusLabel.textColor = .secondaryLabelColor

        let buttonStack = NSStackView()
        buttonStack.orientation = .horizontal
        buttonStack.spacing = 8
        buttonStack.alignment = .centerY

        configureButton(acceptButton, action: #selector(accept))
        configureButton(rejectButton, action: #selector(reject))
        configureButton(silenceButton, action: #selector(silence))
        rejectButton.hasDestructiveAction = true

        buttonStack.addArrangedSubview(acceptButton)
        buttonStack.addArrangedSubview(rejectButton)
        buttonStack.addArrangedSubview(silenceButton)

        [titleLabel, bodyLabel, statusLabel, buttonStack].forEach {
            stack.addArrangedSubview($0)
        }

        contentView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
            stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 14),
            stack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -14),
            titleLabel.widthAnchor.constraint(equalTo: stack.widthAnchor),
            bodyLabel.widthAnchor.constraint(equalTo: stack.widthAnchor)
        ])
    }

    private func configureButton(_ button: NSButton, action: Selector) {
        button.target = self
        button.action = action
        button.bezelStyle = .rounded
        button.controlSize = .regular
    }

    private func startTimer() {
        guard timer == nil else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.updateTimerText()
        }
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
    }

    private func updateTimerText() {
        guard let activeSince else {
            statusLabel.stringValue = "Call active on Android"
            return
        }

        let elapsed = max(0, Int(Date().timeIntervalSince(activeSince)))
        let minutes = elapsed / 60
        let seconds = elapsed % 60
        statusLabel.stringValue = String(format: "Call active %@%02d:%02d", "for ", minutes, seconds)
    }

    private func positionNearTopRight() {
        guard let screen = NSScreen.main else {
            panel.center()
            return
        }

        let visibleFrame = screen.visibleFrame
        let frame = panel.frame
        panel.setFrameOrigin(
            NSPoint(
                x: visibleFrame.maxX - frame.width - 18,
                y: visibleFrame.maxY - frame.height - 18
            )
        )
    }

    @objc private func accept() {
        statusLabel.stringValue = "Accepting on Android..."
        onAction("accept", callId) { [weak self] succeeded, message in
            DispatchQueue.main.async {
                self?.statusLabel.stringValue = succeeded ? "Accepted on Android" : message
            }
        }
    }

    @objc private func reject() {
        statusLabel.stringValue = "Ending on Android..."
        onAction("reject", callId) { [weak self] succeeded, message in
            DispatchQueue.main.async {
                self?.statusLabel.stringValue = succeeded ? "Ended on Android" : message
            }
        }
    }

    @objc private func silence() {
        statusLabel.stringValue = "Silencing Android..."
        onAction("silence", callId) { [weak self] succeeded, message in
            DispatchQueue.main.async {
                self?.statusLabel.stringValue = succeeded ? "Silenced on Android" : message
            }
        }
    }
}
