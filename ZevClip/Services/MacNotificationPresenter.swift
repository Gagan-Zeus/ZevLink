import AppKit
import QuartzCore
import UserNotifications

struct AndroidMirroredNotification: Decodable {
    let event: String?
    let appName: String
    let packageName: String
    let appIconPngBase64: String?
    let notificationImagePngBase64: String?
    let title: String?
    let body: String?
    let subtext: String?
    let actions: [AndroidMirroredNotificationAction]?
    let notificationKey: String?
    let postedAtMillis: Int64?
    let isMediaNotification: Bool?
    let mediaDurationMillis: Int64?
    let mediaPositionMillis: Int64?
    let mediaIsPlaying: Bool?
    let mediaCanSeek: Bool?

    var displayTitle: String {
        let trimmedTitle = title?.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedTitle?.isEmpty == false ? trimmedTitle! : "Notification"
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

    var isMediaPinnedNotification: Bool {
        isMediaNotification == true
    }

    var macNotificationIdentifier: String {
        [
            "android",
            packageName,
            notificationKey ?? "unknown"
        ].joined(separator: ".")
    }
}

struct AndroidMirroredNotificationAction: Decodable {
    let id: String
    let title: String
    let requiresTextInput: Bool?
    let inputLabel: String?

    var cleanTitle: String {
        title
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
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
    var onNotificationAction: ((String, String, String?, @escaping (Bool, String) -> Void) -> Void)?
    var onCallAction: ((String, String, @escaping (Bool, String) -> Void) -> Void)?

    private let center = UNUserNotificationCenter.current()
    private var authorizationRequested = false
    private var activeCallPanel: CallControlPanelController?
    private var activeNotificationPanels: [String: AndroidNotificationPanelController] = [:]
    private var notificationPanelOrder: [String] = []
    private var notificationPanelSerial = 0
    private var retainedNotifications: [String: RetainedAndroidNotification] = [:]
    private var retainedNotificationOrder: [String] = []
    private var notificationShadeStack: AndroidNotificationShadeStackController?
    private var notificationShadeGlobalMonitor: Any?
    private var notificationShadeLocalMonitor: Any?
    private var notificationShadeClickGlobalMonitor: Any?
    private var notificationShadeClickLocalMonitor: Any?
    private var notificationShadeSwipeAmount: CGFloat = 0
    private var didTriggerNotificationShade = false
    private var globalHideSwipeAmount: CGFloat = 0
    private var didTriggerGlobalHide = false

    private override init() {
        super.init()
        center.delegate = self
        installNotificationShadeGestureMonitor()
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
        if notification.isRemoval {
            remove(notification)
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.remember(notification)
            if notification.isMediaPinnedNotification {
                self.hideVisiblePanel(for: notification)
                return
            }
            self.showNotificationPanel(notification)
        }
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
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.forget(notification)
            let matchingPanelIds = self.notificationPanelOrder.filter { panelId in
                self.activeNotificationPanels[panelId]?.matches(notification) == true
            }
            matchingPanelIds.forEach { panelId in
                self.activeNotificationPanels[panelId]?.close()
                self.activeNotificationPanels[panelId] = nil
            }
            self.notificationPanelOrder.removeAll { matchingPanelIds.contains($0) }
            self.repositionNotificationPanels()
        }
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

    private func showNotificationPanel(_ notification: AndroidMirroredNotification) {
        if let existingPanelId = existingNotificationPanelId(for: notification),
           let existingPanel = activeNotificationPanels[existingPanelId] {
            existingPanel.update(notification)
            existingPanel.refreshVisibility()
            repositionNotificationPanels()
            return
        }

        let panelId = nextNotificationPanelId(for: notification)

        let panel = AndroidNotificationPanelController(
            notification: notification,
            icon: Self.appIconImage(for: notification),
            onDismiss: { [weak self] notificationKey in
                self?.dismissAndroidNotificationFromMac(notificationKey)
            },
            onAction: { [weak self] notificationKey, actionId, replyText, completion in
                self?.onNotificationAction?(notificationKey, actionId, replyText, completion)
            },
            onHideAll: { [weak self] in
                self?.hideAllMacNotifications()
            },
            onClose: { [weak self] in
                guard let self else { return }
                self.activeNotificationPanels[panelId] = nil
                self.notificationPanelOrder.removeAll { $0 == panelId }
                self.repositionNotificationPanels()
            },
            onLayoutChange: { [weak self] in
                self?.repositionNotificationPanels()
            }
        )
        activeNotificationPanels[panelId] = panel
        notificationPanelOrder.append(panelId)
        repositionNotificationPanels()
        panel.show(atTopY: notificationPanelTopY(for: panelId))
        NSSound(named: "Glass")?.play()
    }

    private func hideVisiblePanel(for notification: AndroidMirroredNotification) {
        let matchingPanelIds = notificationPanelOrder.filter { panelId in
            activeNotificationPanels[panelId]?.matches(notification) == true
        }
        matchingPanelIds.forEach { panelId in
            activeNotificationPanels[panelId]?.close()
            activeNotificationPanels[panelId] = nil
        }
        notificationPanelOrder.removeAll { matchingPanelIds.contains($0) }
        if !matchingPanelIds.isEmpty {
            repositionNotificationPanels()
        }
    }

    private func remember(_ notification: AndroidMirroredNotification) {
        guard !notification.isRemoval else { return }

        let retainedId = retainedNotificationIdentifier(for: notification)
        if retainedNotifications[retainedId] == nil {
            retainedNotificationOrder.append(retainedId)
        }
        retainedNotifications[retainedId] = RetainedAndroidNotification(
            id: retainedId,
            notification: notification,
            icon: Self.appIconImage(for: notification),
            receivedAt: Date()
        )
        refreshNotificationShade()
    }

    private func forget(_ notification: AndroidMirroredNotification) {
        if let notificationKey = notification.notificationKey?.trimmingCharacters(in: .whitespacesAndNewlines),
           !notificationKey.isEmpty {
            forgetRetainedNotification(matchingKey: notificationKey)
            return
        }

        forgetRetainedNotification(withId: retainedNotificationIdentifier(for: notification))
    }

    private func forgetRetainedNotification(matchingKey notificationKey: String) {
        let matchingIds = retainedNotificationOrder.filter { retainedId in
            retainedNotifications[retainedId]?.notification.notificationKey == notificationKey
        }
        matchingIds.forEach { forgetRetainedNotification(withId: $0) }
        refreshNotificationShade()
    }

    private func forgetRetainedNotification(withId retainedId: String) {
        retainedNotifications[retainedId] = nil
        retainedNotificationOrder.removeAll { $0 == retainedId }
        refreshNotificationShade()
    }

    private func retainedNotificationIdentifier(for notification: AndroidMirroredNotification) -> String {
        if let notificationKey = notification.notificationKey?.trimmingCharacters(in: .whitespacesAndNewlines),
           !notificationKey.isEmpty {
            return notificationKey
        }

        let postedAt = notification.postedAtMillis.map(String.init) ?? "unknown"
        return "\(notification.macNotificationIdentifier).\(postedAt)"
    }

    private func retainedNotificationItems() -> [RetainedAndroidNotification] {
        let items = retainedNotificationOrder
            .reversed()
            .compactMap { retainedNotifications[$0] }
        let mediaItems = items.filter { $0.notification.isMediaPinnedNotification }
        let otherItems = items.filter { !$0.notification.isMediaPinnedNotification }
        return mediaItems + otherItems
    }

    private func showNotificationShade() {
        let items = retainedNotificationItems()
        guard !items.isEmpty else { return }

        if notificationShadeStack == nil {
            notificationShadeStack = AndroidNotificationShadeStackController(
                onDismiss: { [weak self] notificationKey in
                    self?.dismissAndroidNotificationFromMac(notificationKey)
                },
                onAction: { [weak self] notificationKey, actionId, replyText, completion in
                    self?.onNotificationAction?(notificationKey, actionId, replyText, completion)
                },
                onHideAll: { [weak self] in
                    self?.hideAllMacNotifications()
                },
                onClose: { [weak self] in
                    self?.notificationShadeStack = nil
                }
            )
        }
        notificationShadeStack?.show(items: items)
    }

    private func refreshNotificationShade() {
        let items = retainedNotificationItems()
        notificationShadeStack?.update(items: items)
        if items.isEmpty {
            notificationShadeStack?.close()
            notificationShadeStack = nil
        }
    }

    private func dismissAndroidNotificationFromMac(_ notificationKey: String) {
        let trimmedKey = notificationKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedKey.isEmpty else { return }

        forgetRetainedNotification(matchingKey: trimmedKey)
        let matchingPanelIds = notificationPanelOrder.filter { panelId in
            activeNotificationPanels[panelId]?.hasNotificationKey(trimmedKey) == true
        }
        matchingPanelIds.forEach { panelId in
            activeNotificationPanels[panelId]?.close()
        }
        onDismiss?(trimmedKey)
    }

    private func hideAllMacNotifications() {
        Array(activeNotificationPanels.values).forEach { $0.close() }
        notificationShadeStack?.close()
        notificationShadeStack = nil
    }

    private func installNotificationShadeGestureMonitor() {
        notificationShadeGlobalMonitor = NSEvent.addGlobalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
            DispatchQueue.main.async {
                self?.handleNotificationShadeGesture(event)
            }
        }
        notificationShadeLocalMonitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
            self?.handleNotificationShadeGesture(event)
            return event
        }
        let mouseEvents: NSEvent.EventTypeMask = [
            .leftMouseDown,
            .rightMouseDown,
            .otherMouseDown,
            .leftMouseUp,
            .rightMouseUp,
            .otherMouseUp
        ]
        notificationShadeClickGlobalMonitor = NSEvent.addGlobalMonitorForEvents(matching: mouseEvents) { [weak self] event in
            DispatchQueue.main.async {
                self?.hideNotificationShadeIfClickIsOutside(event, isLocalEvent: false)
            }
        }
        notificationShadeClickLocalMonitor = NSEvent.addLocalMonitorForEvents(matching: mouseEvents) { [weak self] event in
            self?.hideNotificationShadeIfClickIsOutside(event, isLocalEvent: true)
            return event
        }
    }

    private func hideNotificationShadeIfClickIsOutside(_ event: NSEvent, isLocalEvent: Bool) {
        guard let notificationShadeStack else { return }
        guard !notificationShadeStack.contains(point: screenPoint(for: event, isLocalEvent: isLocalEvent)) else { return }

        notificationShadeStack.close()
        self.notificationShadeStack = nil
    }

    private func screenPoint(for event: NSEvent, isLocalEvent: Bool) -> NSPoint {
        if isLocalEvent, let window = event.window {
            return window.convertPoint(toScreen: event.locationInWindow)
        }

        let eventLocation = event.locationInWindow
        if eventLocation != .zero {
            return eventLocation
        }

        return NSEvent.mouseLocation
    }

    private func handleNotificationShadeGesture(_ event: NSEvent) {
        if notificationShadeStack?.handleScroll(event) == true {
            resetGlobalHideGestureIfNeeded(event)
            return
        }

        if handleGlobalHideGesture(event) {
            return
        }

        let phase = event.phase
        if phase.contains(.began) || phase.contains(.mayBegin) {
            notificationShadeSwipeAmount = 0
            didTriggerNotificationShade = false
        }

        guard isPointerInTopRightGestureRegion() else {
            notificationShadeSwipeAmount = 0
            return
        }

        let vertical = normalizedFingerVerticalDelta(from: event)
        let horizontal = normalizedFingerHorizontalDelta(from: event)
        let isDownwardSwipe = vertical < 0 && abs(vertical) > abs(horizontal) * 1.25

        if isDownwardSwipe {
            notificationShadeSwipeAmount += abs(vertical)
            if notificationShadeSwipeAmount > Self.notificationShadeSwipeThreshold,
               !didTriggerNotificationShade {
                didTriggerNotificationShade = true
                showNotificationShade()
            }
        }

        if phase.contains(.ended)
            || phase.contains(.cancelled)
            || event.momentumPhase.contains(.ended)
            || event.momentumPhase.contains(.cancelled) {
            notificationShadeSwipeAmount = 0
            didTriggerNotificationShade = false
        }
    }

    private func handleGlobalHideGesture(_ event: NSEvent) -> Bool {
        guard notificationShadeStack != nil || !activeNotificationPanels.isEmpty else {
            resetGlobalHideGestureIfNeeded(event)
            return false
        }

        let phase = event.phase
        if phase.contains(.began) || phase.contains(.mayBegin) {
            globalHideSwipeAmount = 0
            didTriggerGlobalHide = false
        }

        let vertical = normalizedFingerVerticalDelta(from: event)
        let horizontal = normalizedFingerHorizontalDelta(from: event)
        let isUpwardSwipe = vertical > 0 && abs(vertical) > abs(horizontal) * 1.25

        if isUpwardSwipe {
            globalHideSwipeAmount += abs(vertical)
            if globalHideSwipeAmount > Self.globalHideSwipeThreshold,
               !didTriggerGlobalHide {
                didTriggerGlobalHide = true
                hideAllMacNotifications()
                return true
            }
        }

        resetGlobalHideGestureIfNeeded(event)
        return false
    }

    private func resetGlobalHideGestureIfNeeded(_ event: NSEvent) {
        if event.phase.contains(.ended)
            || event.phase.contains(.cancelled)
            || event.momentumPhase.contains(.ended)
            || event.momentumPhase.contains(.cancelled) {
            globalHideSwipeAmount = 0
            didTriggerGlobalHide = false
        }
    }

    private func isPointerInTopRightGestureRegion() -> Bool {
        let mouseLocation = NSEvent.mouseLocation
        let screen = NSScreen.screens.first { NSMouseInRect(mouseLocation, $0.frame, false) } ?? NSScreen.main
        guard let screen else { return false }

        let frame = screen.frame
        return mouseLocation.x >= frame.maxX - Self.notificationShadeGestureWidth
            && mouseLocation.y >= frame.maxY - Self.notificationShadeGestureHeight
    }

    private func normalizedFingerHorizontalDelta(from event: NSEvent) -> CGFloat {
        event.isDirectionInvertedFromDevice ? -event.scrollingDeltaX : event.scrollingDeltaX
    }

    private func normalizedFingerVerticalDelta(from event: NSEvent) -> CGFloat {
        event.isDirectionInvertedFromDevice ? -event.scrollingDeltaY : event.scrollingDeltaY
    }

    private func existingNotificationPanelId(for notification: AndroidMirroredNotification) -> String? {
        if let exactMatch = notificationPanelOrder.first(where: { panelId in
            activeNotificationPanels[panelId]?.matches(notification) == true
        }) {
            return exactMatch
        }

        return notificationPanelOrder.first { panelId in
            activeNotificationPanels[panelId]?.shouldCoalesce(with: notification) == true
        }
    }

    private func nextNotificationPanelId(for notification: AndroidMirroredNotification) -> String {
        notificationPanelSerial += 1
        let postedAt = notification.postedAtMillis.map(String.init) ?? "unknown"
        return "\(notification.macNotificationIdentifier).\(postedAt).\(notificationPanelSerial)"
    }

    private func repositionNotificationPanels() {
        var nextTopY = notificationPanelInitialTopY()
        for panelId in notificationPanelOrder {
            guard let panel = activeNotificationPanels[panelId] else { continue }
            panel.move(toTopY: nextTopY)
            nextTopY -= panel.currentHeight + 10
        }
    }

    private func notificationPanelTopY(for panelId: String) -> CGFloat {
        var nextTopY = notificationPanelInitialTopY()
        for orderedPanelId in notificationPanelOrder {
            guard let panel = activeNotificationPanels[orderedPanelId] else { continue }
            if orderedPanelId == panelId {
                return nextTopY
            }
            nextTopY -= panel.currentHeight + 10
        }
        return nextTopY
    }

    private func notificationPanelInitialTopY() -> CGFloat {
        guard let screen = NSScreen.main else {
            return 0
        }

        return screen.visibleFrame.maxY - 18
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

        await MainActor.run {
            dismissAndroidNotificationFromMac(notificationKey)
        }
    }

    private static let androidNotificationCategory = "ANDROID_MIRRORED_NOTIFICATION"
    private static let androidCallCategory = "ANDROID_MIRRORED_CALL"
    private static let callActionAccept = "ANDROID_CALL_ACCEPT"
    private static let callActionReject = "ANDROID_CALL_REJECT"
    private static let callActionSilence = "ANDROID_CALL_SILENCE"
    private static let maximumImageBytes = 512 * 1024
    private static let notificationShadeSwipeThreshold: CGFloat = 90
    private static let globalHideSwipeThreshold: CGFloat = 70
    private static let notificationShadeGestureWidth: CGFloat = 280
    private static let notificationShadeGestureHeight: CGFloat = 170

    fileprivate static func appIconImage(
        for notification: AndroidMirroredNotification
    ) -> NSImage? {
        if let notificationImage = image(fromBase64Png: notification.notificationImagePngBase64) {
            return notificationImage
        }

        return image(fromBase64Png: notification.appIconPngBase64)
    }

    private static func image(fromBase64Png encodedImage: String?) -> NSImage? {
        guard
            let encodedImage,
            let imageData = Data(base64Encoded: encodedImage, options: [.ignoreUnknownCharacters]),
            !imageData.isEmpty,
            imageData.count <= maximumImageBytes,
            imageData.starts(with: [0x89, 0x50, 0x4e, 0x47])
        else { return nil }

        return NSImage(data: imageData)
    }
}

private struct RetainedAndroidNotification {
    let id: String
    let notification: AndroidMirroredNotification
    let icon: NSImage?
    let receivedAt: Date
}

private final class AndroidNotificationShadeStackController {
    private var panels: [String: AndroidNotificationPanelController] = [:]
    private var order: [String] = []
    private let onDismiss: (String) -> Void
    private let onAction: (String, String, String?, @escaping (Bool, String) -> Void) -> Void
    private let onHideAll: () -> Void
    private let onClose: () -> Void
    private var scrollOffset: CGFloat = 0
    private var isClosing = false

    private static let panelSpacing: CGFloat = 10
    private static let topPadding: CGFloat = 18

    init(
        onDismiss: @escaping (String) -> Void,
        onAction: @escaping (String, String, String?, @escaping (Bool, String) -> Void) -> Void,
        onHideAll: @escaping () -> Void,
        onClose: @escaping () -> Void
    ) {
        self.onDismiss = onDismiss
        self.onAction = onAction
        self.onHideAll = onHideAll
        self.onClose = onClose
    }

    func show(items: [RetainedAndroidNotification]) {
        update(items: items)
    }

    func update(items: [RetainedAndroidNotification]) {
        guard !items.isEmpty else {
            close()
            return
        }

        let incomingIds = Set(items.map(\.id))
        let staleIds = order.filter { !incomingIds.contains($0) }
        staleIds.forEach { id in
            panels[id]?.close()
            panels[id] = nil
        }

        order = items.map(\.id)
        for item in items {
            if let existingPanel = panels[item.id] {
                existingPanel.update(item.notification, mediaSnapshotReceivedAt: item.receivedAt)
            } else {
                let panel = AndroidNotificationPanelController(
                    notification: item.notification,
                    icon: item.icon,
                    onDismiss: { [weak self] notificationKey in
                        self?.onDismiss(notificationKey)
                    },
                    onAction: { [weak self] notificationKey, actionId, replyText, completion in
                        self?.onAction(notificationKey, actionId, replyText, completion)
                    },
                    onHideAll: { [weak self] in
                        self?.onHideAll()
                    },
                    onClose: { [weak self] in
                        self?.panels[item.id] = nil
                        self?.order.removeAll { $0 == item.id }
                    },
                    onLayoutChange: { [weak self] in
                        self?.reposition(orderFront: false)
                    },
                    shouldAutoClose: false,
                    mediaSnapshotReceivedAt: item.receivedAt
                )
                panels[item.id] = panel
            }
        }

        clampScrollOffset()
        reposition(orderFront: true)
    }

    func handleScroll(_ event: NSEvent) -> Bool {
        guard !panels.isEmpty, pointerIsInsideStack() else {
            return false
        }

        let horizontal = normalizedFingerHorizontalDelta(from: event)
        let vertical = normalizedFingerVerticalDelta(from: event)
        guard abs(vertical) > abs(horizontal), abs(vertical) > 0.2 else {
            return false
        }

        scrollOffset -= vertical
        clampScrollOffset()
        reposition(orderFront: false)
        return true
    }

    func close() {
        guard !isClosing else { return }
        isClosing = true
        Array(panels.values).forEach { $0.close() }
        panels.removeAll()
        order.removeAll()
        onClose()
    }

    private func reposition(orderFront: Bool) {
        var nextTopY = initialTopY() - scrollOffset
        for id in order {
            guard let panel = panels[id] else { continue }
            if orderFront {
                panel.show(atTopY: nextTopY)
            } else {
                panel.move(toTopY: nextTopY)
            }
            nextTopY -= panel.currentHeight + Self.panelSpacing
        }
    }

    private func pointerIsInsideStack() -> Bool {
        contains(point: NSEvent.mouseLocation)
    }

    func contains(point: NSPoint) -> Bool {
        return panels.values.contains { panel in
            NSMouseInRect(point, panel.screenFrame.insetBy(dx: -12, dy: -12), false)
        }
    }

    private func clampScrollOffset() {
        scrollOffset = min(max(scrollOffset, 0), maximumScrollOffset())
    }

    private func maximumScrollOffset() -> CGFloat {
        max(0, contentHeight() - availableHeight())
    }

    private func contentHeight() -> CGFloat {
        let heights = order.compactMap { panels[$0]?.currentHeight }
        guard !heights.isEmpty else { return 0 }
        return heights.reduce(0, +) + (CGFloat(heights.count - 1) * Self.panelSpacing)
    }

    private func availableHeight() -> CGFloat {
        guard let screen = NSScreen.main else { return 0 }
        return max(0, screen.visibleFrame.height - (Self.topPadding * 2))
    }

    private func initialTopY() -> CGFloat {
        guard let screen = NSScreen.main else { return 0 }
        return screen.visibleFrame.maxY - Self.topPadding
    }

    private func normalizedFingerHorizontalDelta(from event: NSEvent) -> CGFloat {
        event.isDirectionInvertedFromDevice ? -event.scrollingDeltaX : event.scrollingDeltaX
    }

    private func normalizedFingerVerticalDelta(from event: NSEvent) -> CGFloat {
        event.isDirectionInvertedFromDevice ? -event.scrollingDeltaY : event.scrollingDeltaY
    }
}

private final class AndroidNotificationPanelController: NSObject {
    private let panel: NotificationInteractionPanel
    private let iconView = NSImageView()
    private let titleLabel = MarqueeTextField(labelWithString: "")
    private let bodyLabel = MarqueeTextField(labelWithString: "")
    private let expandButton = NotificationActionButton(title: "", target: nil, action: nil)
    private let actionContainerView = NSView()
    private let actionContentStack = NSStackView()
    private let mediaTimelineView = MediaTimelineControl()
    private let actionStack = NSStackView()
    private let replyComposerView = NSStackView()
    private let replyBackButton = NotificationActionButton(title: "", target: nil, action: nil)
    private let replyFieldContainer = NSView()
    private let replyTextField = InlineReplyTextField(string: "")
    private let replyCursorView = NSView()
    private let replySendButton = NotificationActionButton(title: "", target: nil, action: nil)
    private let onDismiss: (String) -> Void
    private let onAction: (String, String, String?, @escaping (Bool, String) -> Void) -> Void
    private let onHideAll: () -> Void
    private let onClose: () -> Void
    private let onLayoutChange: () -> Void
    private let shouldAutoClose: Bool
    private var notificationKey: String?
    private var notificationKeys = Set<String>()
    private var packageName = ""
    private var coalescingTitle = ""
    private var notificationActions: [AndroidMirroredNotificationAction] = []
    private var isMediaNotification = false
    private var mediaDurationMillis: Int64?
    private var mediaPositionMillis: Int64?
    private var mediaPositionUpdatedAt: Date?
    private var mediaIsPlaying = false
    private var mediaCanSeek = false
    private var isUpdatingMediaTimelineSlider = false
    private var detectedOTPCode: String?
    private var activeReplyAction: AndroidMirroredNotificationAction?
    private var replyKeyMonitor: Any?
    private var autoCloseTimer: Timer?
    private var mediaTimelineTimer: Timer?
    private var isClosing = false
    private var isExpanded = false
    private var isHovering = false
    private var hasLongContent = false
    private var targetTopY: CGFloat?
    private var fullTitle = ""
    private var fullBody = ""
    private var iconCenterYConstraint: NSLayoutConstraint?
    private var iconTopConstraint: NSLayoutConstraint?
    private var iconWidthConstraint: NSLayoutConstraint?
    private var iconHeightConstraint: NSLayoutConstraint?
    private var textCenterYConstraint: NSLayoutConstraint?
    private var textTopConstraint: NSLayoutConstraint?
    private var textStackWidthConstraint: NSLayoutConstraint?
    private var textTrailingToExpandConstraint: NSLayoutConstraint?
    private var textTrailingToContentConstraint: NSLayoutConstraint?
    private var textAboveActionsConstraint: NSLayoutConstraint?
    private var actionBelowTextConstraint: NSLayoutConstraint?
    private var actionContainerHeightConstraint: NSLayoutConstraint?
    private var replyCursorLeadingConstraint: NSLayoutConstraint?
    private var actionStackWidthConstraint: NSLayoutConstraint?
    private var mediaTimelineWidthConstraint: NSLayoutConstraint?
    private var mediaTimelineHeightConstraint: NSLayoutConstraint?
    private weak var trackingContentView: HoverTrackingView?
    private var replyCursorBlinkTimer: Timer?
    private static let textColumnWidth: CGFloat = 240
    private static let mediaTextColumnWidth: CGFloat = 186
    private static let regularIconSize: CGFloat = 40
    private static let mediaIconSize: CGFloat = 120
    private static let collapsedHeight: CGFloat = 72
    private static let mediaCollapsedHeight: CGFloat = 154
    private static let hoverActionsHeight: CGFloat = 106
    private static let actionRowHeight: CGFloat = 32
    private static let mediaActionRowHeight: CGFloat = 38
    private static let mediaTimelineRowHeight: CGFloat = 30
    private static let mediaTimelineSpacing: CGFloat = 5
    private static let expandedBodyLineLimit = 4
    private static let expandedMaxHeight: CGFloat = 210
    private static let expandedMinHeight: CGFloat = 80
    private static let defaultVisibleDuration: TimeInterval = 7
    private static let unhoverVisibleDuration: TimeInterval = 3
    private static let hoverAnimationDuration: TimeInterval = 0.32
    private static let activeMediaDisplayOffsetMillis: Int64 = 1000
    private static let copyOTPActionId = "zevlink.copy-otp-code"
    private static let mediaSeekActionId = "zevlink.media-seek-to"

    init(
        notification: AndroidMirroredNotification,
        icon: NSImage?,
        onDismiss: @escaping (String) -> Void,
        onAction: @escaping (String, String, String?, @escaping (Bool, String) -> Void) -> Void,
        onHideAll: @escaping () -> Void,
        onClose: @escaping () -> Void,
        onLayoutChange: @escaping () -> Void,
        shouldAutoClose: Bool = true,
        mediaSnapshotReceivedAt: Date = Date()
    ) {
        self.onDismiss = onDismiss
        self.onAction = onAction
        self.onHideAll = onHideAll
        self.onClose = onClose
        self.onLayoutChange = onLayoutChange
        self.shouldAutoClose = shouldAutoClose

        panel = NotificationInteractionPanel(
            contentRect: NSRect(x: 0, y: 0, width: 352, height: Self.collapsedHeight),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.ignoresMouseEvents = false
        panel.isMovableByWindowBackground = false
        panel.appearance = NSApp.effectiveAppearance

        super.init()
        configureContent()
        update(notification, icon: icon, mediaSnapshotReceivedAt: mediaSnapshotReceivedAt)
    }

    var currentHeight: CGFloat {
        panel.frame.height
    }

    var screenFrame: NSRect {
        panel.frame
    }

    func show(atTopY topY: CGFloat) {
        move(toTopY: topY)
        updateMediaTimelineSlider()
        updateMediaTimelineTimer()
        panel.orderFrontRegardless()
        if shouldAutoClose {
            restartAutoCloseTimer(after: Self.defaultVisibleDuration)
        }
    }

    func move(toTopY topY: CGFloat) {
        positionNearTopRight(topY: topY)
    }

    func update(_ notification: AndroidMirroredNotification) {
        update(notification, icon: MacNotificationPresenter.appIconImage(for: notification), mediaSnapshotReceivedAt: Date())
    }

    func update(_ notification: AndroidMirroredNotification, mediaSnapshotReceivedAt: Date) {
        update(
            notification,
            icon: MacNotificationPresenter.appIconImage(for: notification),
            mediaSnapshotReceivedAt: mediaSnapshotReceivedAt
        )
    }

    func refreshVisibility() {
        updateMediaTimelineSlider()
        updateMediaTimelineTimer()
        panel.orderFrontRegardless()
        if shouldAutoClose && !isHovering && activeReplyAction == nil {
            restartAutoCloseTimer(after: Self.defaultVisibleDuration)
        }
    }

    func matches(_ notification: AndroidMirroredNotification) -> Bool {
        guard let removedKey = notification.notificationKey, !removedKey.isEmpty else {
            return false
        }
        return notificationKeys.contains(removedKey) || notificationKey == removedKey
    }

    func hasNotificationKey(_ notificationKey: String) -> Bool {
        notificationKeys.contains(notificationKey) || self.notificationKey == notificationKey
    }

    func shouldCoalesce(with notification: AndroidMirroredNotification) -> Bool {
        guard !notification.isRemoval else { return false }
        let incomingTitle = Self.singleLine(notification.displayTitle)
        return !packageName.isEmpty
            && packageName == notification.packageName
            && !coalescingTitle.isEmpty
            && coalescingTitle == incomingTitle
    }

    func close() {
        guard !isClosing else { return }
        isClosing = true
        autoCloseTimer?.invalidate()
        stopMediaTimelineTimer()
        titleLabel.setMarqueeActive(false)
        bodyLabel.setMarqueeActive(false)
        stopInlineReplyKeyCapture()
        stopReplyCursorBlink()
        panel.close()
        onClose()
    }

    private func update(
        _ notification: AndroidMirroredNotification,
        icon: NSImage?,
        mediaSnapshotReceivedAt: Date
    ) {
        if let incomingKey = notification.notificationKey?.trimmingCharacters(in: .whitespacesAndNewlines),
           !incomingKey.isEmpty {
            notificationKey = incomingKey
            notificationKeys.insert(incomingKey)
        }
        packageName = notification.packageName
        isMediaNotification = notification.isMediaPinnedNotification
        if
            let duration = notification.mediaDurationMillis,
            duration > 0,
            let position = notification.mediaPositionMillis,
            position >= 0
        {
            mediaDurationMillis = duration
            mediaPositionMillis = min(position, duration)
            mediaPositionUpdatedAt = mediaSnapshotReceivedAt
            mediaIsPlaying = notification.mediaIsPlaying == true
            mediaCanSeek = notification.mediaCanSeek == true
        } else {
            mediaDurationMillis = nil
            mediaPositionMillis = nil
            mediaPositionUpdatedAt = nil
            mediaIsPlaying = false
            mediaCanSeek = false
        }
        fullTitle = Self.singleLine(notification.displayTitle)
        coalescingTitle = fullTitle
        fullBody = Self.singleLine(notification.displayBody)
        detectedOTPCode = Self.detectOTPCode(in: [
            notification.title,
            notification.body,
            notification.subtext
        ])
        let wasReplying = activeReplyAction != nil
        let maximumAndroidActions = detectedOTPCode == nil ? 4 : 3
        notificationActions = notification.actions?
            .filter { !$0.id.isEmpty && !$0.cleanTitle.isEmpty }
            .prefix(maximumAndroidActions)
            .map { $0 } ?? []
        if let activeReplyAction,
           !notificationActions.contains(where: { $0.id == activeReplyAction.id }) {
            self.activeReplyAction = nil
            stopInlineReplyKeyCapture()
            stopReplyCursorBlink()
        }
        rebuildActionButtons()
        updateMediaTimelineSlider()
        updateMediaTimelineTimer()
        updateMediaMarqueeState()
        hasLongContent = Self.requiresExpansion(
            title: fullTitle,
            body: fullBody,
            titleFont: titleLabel.font ?? .systemFont(ofSize: 14, weight: .semibold),
            bodyFont: bodyLabel.font ?? .systemFont(ofSize: 13)
        )
        if !hasLongContent {
            isExpanded = false
        }
        applyDisplayState(animated: false)
        iconView.image = icon ?? NSApp.applicationIconImage
        if wasReplying && activeReplyAction == nil {
            restartAutoCloseTimer(after: Self.unhoverVisibleDuration)
        }
    }

    private func configureContent() {
        let contentView = HoverTrackingView(frame: panel.contentView?.bounds ?? NSRect(x: 0, y: 0, width: 352, height: Self.collapsedHeight))
        trackingContentView = contentView
        contentView.onHoverChanged = { [weak self] isHovering in
            self?.isHovering = isHovering
            self?.applyDisplayState(animated: true)
            if isHovering {
                self?.autoCloseTimer?.invalidate()
                self?.autoCloseTimer = nil
            } else {
                self?.restartAutoCloseTimer(after: Self.unhoverVisibleDuration)
            }
        }
        contentView.onSwipeRight = { [weak self] in
            self?.dismissFromAndroid()
        }
        contentView.onSwipeUp = { [weak self] in
            self?.onHideAll()
        }
        contentView.allowsScrollWheelSwipeUp = shouldAutoClose
        configurePanelContentView(contentView)
        panel.acceptsMouseMovedEvents = true

        iconView.imageScaling = .scaleProportionallyUpOrDown
        iconView.wantsLayer = true
        iconView.layer?.cornerRadius = 9
        iconView.layer?.masksToBounds = true
        iconView.translatesAutoresizingMaskIntoConstraints = false

        titleLabel.font = .systemFont(ofSize: 14, weight: .semibold)
        titleLabel.textColor = .labelColor
        titleLabel.lineBreakMode = .byTruncatingTail
        titleLabel.maximumNumberOfLines = 1
        titleLabel.cell?.usesSingleLineMode = true
        titleLabel.cell?.truncatesLastVisibleLine = true
        titleLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        titleLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
        bodyLabel.font = .systemFont(ofSize: 13)
        bodyLabel.textColor = .secondaryLabelColor
        bodyLabel.lineBreakMode = .byTruncatingTail
        bodyLabel.maximumNumberOfLines = 1
        bodyLabel.cell?.usesSingleLineMode = true
        bodyLabel.cell?.truncatesLastVisibleLine = true
        bodyLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        bodyLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)

        expandButton.target = self
        expandButton.action = #selector(toggleExpanded)
        expandButton.isHidden = true
        if let chevronImage = Self.chevronImage(named: "chevron.right", accessibilityDescription: "Expand") {
            expandButton.image = chevronImage
        } else {
            expandButton.title = ">"
            expandButton.font = .systemFont(ofSize: 11, weight: .semibold)
        }
        expandButton.contentTintColor = .labelColor
        expandButton.isEnabled = true
        expandButton.isBordered = false
        expandButton.wantsLayer = false
        expandButton.translatesAutoresizingMaskIntoConstraints = false

        actionStack.orientation = .horizontal
        actionStack.alignment = .centerY
        actionStack.spacing = 10
        actionStack.isHidden = true
        actionStack.alphaValue = 0
        actionStack.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        actionStack.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        actionStack.translatesAutoresizingMaskIntoConstraints = false
        actionStackWidthConstraint = actionStack.widthAnchor.constraint(equalToConstant: Self.textColumnWidth)

        actionContentStack.orientation = .vertical
        actionContentStack.alignment = .leading
        actionContentStack.spacing = Self.mediaTimelineSpacing
        actionContentStack.isHidden = true
        actionContentStack.alphaValue = 0
        actionContentStack.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        actionContentStack.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        actionContentStack.translatesAutoresizingMaskIntoConstraints = false

        mediaTimelineView.target = self
        mediaTimelineView.action = #selector(seekMediaTimeline(_:))
        mediaTimelineView.isEnabled = false
        mediaTimelineView.translatesAutoresizingMaskIntoConstraints = false
        mediaTimelineView.setContentHuggingPriority(.defaultLow, for: .horizontal)
        mediaTimelineView.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        mediaTimelineWidthConstraint = mediaTimelineView.widthAnchor.constraint(equalToConstant: Self.mediaTextColumnWidth)
        mediaTimelineHeightConstraint = mediaTimelineView.heightAnchor.constraint(equalToConstant: Self.mediaTimelineRowHeight)
        mediaTimelineWidthConstraint?.isActive = true
        mediaTimelineHeightConstraint?.isActive = true

        replyFieldContainer.wantsLayer = true
        replyFieldContainer.layer?.backgroundColor = NSColor.white.withAlphaComponent(0.12).cgColor
        replyFieldContainer.layer?.cornerRadius = 14
        replyFieldContainer.layer?.masksToBounds = true
        replyFieldContainer.translatesAutoresizingMaskIntoConstraints = false

        replyTextField.placeholderString = "Reply"
        replyTextField.font = .systemFont(ofSize: 13, weight: .medium)
        replyTextField.textColor = .labelColor
        replyTextField.placeholderAttributedString = Self.placeholderString("Reply")
        replyTextField.backgroundColor = .clear
        replyTextField.drawsBackground = false
        replyTextField.isBordered = false
        replyTextField.focusRingType = .none
        replyTextField.lineBreakMode = .byTruncatingTail
        replyTextField.cell?.usesSingleLineMode = true
        replyTextField.cell?.wraps = false
        replyTextField.cell?.isScrollable = true
        replyTextField.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        replyTextField.translatesAutoresizingMaskIntoConstraints = false
        replyFieldContainer.addSubview(replyTextField)

        replyCursorView.wantsLayer = true
        replyCursorView.layer?.backgroundColor = NSColor.controlAccentColor.cgColor
        replyCursorView.layer?.cornerRadius = 1
        replyCursorView.translatesAutoresizingMaskIntoConstraints = false
        replyCursorView.isHidden = true
        replyFieldContainer.addSubview(replyCursorView)

        replyBackButton.target = self
        replyBackButton.action = #selector(cancelInlineReply)
        replyBackButton.isBordered = false
        replyBackButton.wantsLayer = true
        replyBackButton.layer?.cornerRadius = 14
        replyBackButton.layer?.masksToBounds = true
        replyBackButton.layer?.backgroundColor = NSColor.white.withAlphaComponent(0.10).cgColor
        replyBackButton.contentTintColor = .white
        replyBackButton.focusRingType = .none
        replyBackButton.imagePosition = .imageOnly
        if let backImage = NSImage(systemSymbolName: "chevron.left", accessibilityDescription: "Back to notification actions") {
            replyBackButton.image = backImage.withSymbolConfiguration(.init(pointSize: 11, weight: .semibold))
        } else {
            replyBackButton.title = "<"
        }
        replyBackButton.translatesAutoresizingMaskIntoConstraints = false

        replySendButton.target = self
        replySendButton.action = #selector(sendInlineReply)
        replySendButton.isBordered = false
        replySendButton.wantsLayer = true
        replySendButton.layer?.cornerRadius = 14
        replySendButton.layer?.masksToBounds = false
        replySendButton.layer?.backgroundColor = NSColor.white.withAlphaComponent(0.12).cgColor
        replySendButton.contentTintColor = .white
        replySendButton.focusRingType = .none
        replySendButton.imagePosition = .imageOnly
        if let sendImage = NSImage(systemSymbolName: "paperplane.fill", accessibilityDescription: "Send reply") {
            replySendButton.image = sendImage.withSymbolConfiguration(.init(pointSize: 11, weight: .semibold))
        } else {
            replySendButton.title = ">"
        }
        replySendButton.translatesAutoresizingMaskIntoConstraints = false

        replyComposerView.orientation = .horizontal
        replyComposerView.alignment = .centerY
        replyComposerView.spacing = 8
        replyComposerView.isHidden = true
        replyComposerView.alphaValue = 0
        replyComposerView.translatesAutoresizingMaskIntoConstraints = false
        replyComposerView.addArrangedSubview(replyBackButton)
        replyComposerView.addArrangedSubview(replyFieldContainer)
        replyComposerView.addArrangedSubview(replySendButton)
        replyBackButton.widthAnchor.constraint(equalToConstant: 28).isActive = true
        replyBackButton.heightAnchor.constraint(equalToConstant: 28).isActive = true
        replyFieldContainer.widthAnchor.constraint(equalToConstant: 160).isActive = true
        replyFieldContainer.heightAnchor.constraint(equalToConstant: 28).isActive = true
        replySendButton.widthAnchor.constraint(equalToConstant: 34).isActive = true
        replySendButton.heightAnchor.constraint(equalToConstant: 28).isActive = true

        actionContainerView.wantsLayer = true
        actionContainerView.layer?.masksToBounds = false
        actionContainerView.isHidden = true
        actionContainerView.alphaValue = 0
        actionContainerView.translatesAutoresizingMaskIntoConstraints = false
        actionContainerView.addSubview(actionContentStack)
        actionContainerView.addSubview(replyComposerView)

        let textStack = NSStackView(views: [titleLabel, bodyLabel])
        textStack.orientation = .vertical
        textStack.alignment = .leading
        textStack.spacing = 1
        textStack.translatesAutoresizingMaskIntoConstraints = false

        [iconView, textStack, expandButton, actionContainerView].forEach {
            contentView.addSubview($0)
        }

        let iconCenterY = iconView.centerYAnchor.constraint(equalTo: contentView.centerYAnchor)
        let iconTop = iconView.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 18)
        let textCenterY = textStack.centerYAnchor.constraint(equalTo: contentView.centerYAnchor)
        let textTop = textStack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 18)
        let iconWidth = iconView.widthAnchor.constraint(equalToConstant: Self.regularIconSize)
        let iconHeight = iconView.heightAnchor.constraint(equalToConstant: Self.regularIconSize)
        let textStackWidth = textStack.widthAnchor.constraint(equalToConstant: Self.textColumnWidth)
        let textTrailingToExpand = textStack.trailingAnchor.constraint(equalTo: expandButton.leadingAnchor, constant: -10)
        let textTrailingToContent = textStack.trailingAnchor.constraint(lessThanOrEqualTo: contentView.trailingAnchor, constant: -18)
        let textAboveActions = textStack.bottomAnchor.constraint(lessThanOrEqualTo: actionContainerView.topAnchor, constant: -6)
        let actionBelowText = actionContainerView.topAnchor.constraint(equalTo: textStack.bottomAnchor, constant: 8)
        let actionHeight = actionContainerView.heightAnchor.constraint(equalToConstant: 0)
        let replyCursorLeading = replyCursorView.leadingAnchor.constraint(equalTo: replyFieldContainer.leadingAnchor, constant: 12)
        iconCenterYConstraint = iconCenterY
        iconTopConstraint = iconTop
        iconWidthConstraint = iconWidth
        iconHeightConstraint = iconHeight
        textCenterYConstraint = textCenterY
        textTopConstraint = textTop
        textStackWidthConstraint = textStackWidth
        textTrailingToExpandConstraint = textTrailingToExpand
        textTrailingToContentConstraint = textTrailingToContent
        textAboveActionsConstraint = textAboveActions
        actionBelowTextConstraint = actionBelowText
        actionContainerHeightConstraint = actionHeight
        replyCursorLeadingConstraint = replyCursorLeading

        NSLayoutConstraint.activate([
            iconView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 14),
            iconCenterY,
            iconWidth,
            iconHeight,

            textStack.leadingAnchor.constraint(equalTo: iconView.trailingAnchor, constant: 12),
            textTrailingToExpand,
            textCenterY,
            textStack.topAnchor.constraint(greaterThanOrEqualTo: contentView.topAnchor, constant: 14),
            textStack.bottomAnchor.constraint(lessThanOrEqualTo: contentView.bottomAnchor, constant: -4),
            textStackWidth,
            titleLabel.widthAnchor.constraint(equalTo: textStack.widthAnchor),
            bodyLabel.widthAnchor.constraint(equalTo: textStack.widthAnchor),

            expandButton.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
            expandButton.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 14),
            expandButton.widthAnchor.constraint(equalToConstant: 18),
            expandButton.heightAnchor.constraint(equalToConstant: 18),

            actionContainerView.leadingAnchor.constraint(equalTo: textStack.leadingAnchor),
            actionContainerView.trailingAnchor.constraint(lessThanOrEqualTo: contentView.trailingAnchor, constant: -16),
            actionContainerView.bottomAnchor.constraint(lessThanOrEqualTo: contentView.bottomAnchor, constant: -6),
            actionHeight,

            actionContentStack.leadingAnchor.constraint(equalTo: actionContainerView.leadingAnchor),
            actionContentStack.trailingAnchor.constraint(lessThanOrEqualTo: actionContainerView.trailingAnchor),
            actionContentStack.centerYAnchor.constraint(equalTo: actionContainerView.centerYAnchor),

            replyTextField.leadingAnchor.constraint(equalTo: replyFieldContainer.leadingAnchor, constant: 12),
            replyTextField.trailingAnchor.constraint(equalTo: replyFieldContainer.trailingAnchor, constant: -10),
            replyTextField.centerYAnchor.constraint(equalTo: replyFieldContainer.centerYAnchor),
            replyTextField.heightAnchor.constraint(equalToConstant: 20),
            replyCursorLeading,
            replyCursorView.centerYAnchor.constraint(equalTo: replyFieldContainer.centerYAnchor),
            replyCursorView.widthAnchor.constraint(equalToConstant: 2),
            replyCursorView.heightAnchor.constraint(equalToConstant: 16),

            replyComposerView.leadingAnchor.constraint(equalTo: actionContainerView.leadingAnchor),
            replyComposerView.trailingAnchor.constraint(lessThanOrEqualTo: actionContainerView.trailingAnchor),
            replyComposerView.centerYAnchor.constraint(equalTo: actionContainerView.centerYAnchor)
        ])
    }

    private func configurePanelContentView(_ contentView: HoverTrackingView) {
        if #available(macOS 26.0, *) {
            let glassView = NSGlassEffectView(frame: contentView.frame)
            glassView.cornerRadius = 18
            glassView.autoresizingMask = [.width, .height]
            contentView.frame = glassView.bounds
            contentView.autoresizingMask = [.width, .height]
            glassView.contentView = contentView
            panel.contentView = glassView

            contentView.wantsLayer = true
            contentView.layer?.backgroundColor = NSColor.clear.cgColor
            contentView.layer?.masksToBounds = true
            contentView.focusRingType = .none
        } else {
            panel.contentView = contentView
            contentView.wantsLayer = true
            contentView.layer?.backgroundColor = NSColor(calibratedWhite: 0.07, alpha: 0.96).cgColor
            contentView.layer?.cornerRadius = 18
            contentView.layer?.masksToBounds = true
            contentView.focusRingType = .none
        }
    }

    private func restartAutoCloseTimer(after interval: TimeInterval) {
        autoCloseTimer?.invalidate()
        guard shouldAutoClose, !isHovering, activeReplyAction == nil else {
            autoCloseTimer = nil
            return
        }
        autoCloseTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: false) { [weak self] _ in
            self?.close()
        }
    }

    private func positionNearTopRight(topY: CGFloat) {
        guard let screen = NSScreen.main else {
            panel.center()
            return
        }

        let visibleFrame = screen.visibleFrame
        let frame = panel.frame
        targetTopY = topY
        panel.setFrameOrigin(
            NSPoint(
                x: visibleFrame.maxX - frame.width - 18,
                y: topY - frame.height
            )
        )
    }

    @objc private func toggleExpanded() {
        guard hasLongContent else { return }
        isExpanded.toggle()
        applyDisplayState(animated: true)
        restartAutoCloseTimer(after: Self.defaultVisibleDuration)
    }

    private func applyDisplayState(animated: Bool) {
        let shouldShowActionRow = shouldShowMediaTimeline || detectedOTPCode != nil || !notificationActions.isEmpty || activeReplyAction != nil
        let shouldFadeActionsOut = animated && !shouldShowActionRow && !actionContainerView.isHidden
        let keepsActionRowVisible = shouldShowActionRow || shouldFadeActionsOut
        let actionContentHeight = currentActionContentHeight()
        let usesTopLayout = isExpanded || isMediaNotification
        let actionOffset: CGFloat = keepsActionRowVisible && !usesTopLayout ? -max(0, (actionContentHeight / 2) - 2) : 0

        iconWidthConstraint?.constant = isMediaNotification ? Self.mediaIconSize : Self.regularIconSize
        iconHeightConstraint?.constant = isMediaNotification ? Self.mediaIconSize : Self.regularIconSize
        iconView.layer?.cornerRadius = isMediaNotification ? 13 : 9
        textStackWidthConstraint?.constant = isMediaNotification ? Self.mediaTextColumnWidth : Self.textColumnWidth
        actionStackWidthConstraint?.constant = isMediaNotification ? Self.mediaTextColumnWidth : Self.textColumnWidth
        mediaTimelineWidthConstraint?.constant = Self.mediaTextColumnWidth
        iconTopConstraint?.constant = isMediaNotification ? 17 : 18
        textTopConstraint?.constant = isMediaNotification ? 20 : 18
        actionBelowTextConstraint?.constant = isMediaNotification ? 10 : 8
        titleLabel.font = .systemFont(ofSize: isMediaNotification ? 16 : 14, weight: .semibold)
        bodyLabel.font = .systemFont(ofSize: isMediaNotification ? 14 : 13, weight: .medium)
        textTrailingToExpandConstraint?.isActive = !isMediaNotification
        textTrailingToContentConstraint?.isActive = isMediaNotification

        trackingContentView?.layoutSubtreeIfNeeded()

        if isMediaNotification {
            updateMediaMarqueeState()
            titleLabel.stringValue = fullTitle
            titleLabel.lineBreakMode = .byClipping
            titleLabel.maximumNumberOfLines = 1
            titleLabel.cell?.usesSingleLineMode = true
            titleLabel.cell?.wraps = false
            bodyLabel.stringValue = fullBody
            bodyLabel.lineBreakMode = .byClipping
            bodyLabel.maximumNumberOfLines = 1
            bodyLabel.cell?.usesSingleLineMode = true
            bodyLabel.cell?.wraps = false
        } else if isExpanded {
            titleLabel.stringValue = fullTitle
            titleLabel.lineBreakMode = .byWordWrapping
            titleLabel.maximumNumberOfLines = 1
            titleLabel.cell?.usesSingleLineMode = false
            titleLabel.cell?.wraps = true
            bodyLabel.stringValue = fullBody
            bodyLabel.lineBreakMode = .byWordWrapping
            bodyLabel.maximumNumberOfLines = Self.expandedBodyLineLimit
            bodyLabel.cell?.usesSingleLineMode = false
            bodyLabel.cell?.wraps = true
            bodyLabel.cell?.truncatesLastVisibleLine = true
        } else {
            titleLabel.stringValue = Self.fittedSingleLine(
                fullTitle,
                font: titleLabel.font ?? .systemFont(ofSize: 14, weight: .semibold)
            )
            titleLabel.lineBreakMode = .byTruncatingTail
            titleLabel.maximumNumberOfLines = 1
            titleLabel.cell?.usesSingleLineMode = true
            titleLabel.cell?.wraps = false
            bodyLabel.stringValue = Self.fittedSingleLine(
                fullBody,
                font: bodyLabel.font ?? .systemFont(ofSize: 13)
            )
            bodyLabel.lineBreakMode = .byTruncatingTail
            bodyLabel.maximumNumberOfLines = 1
            bodyLabel.cell?.usesSingleLineMode = true
            bodyLabel.cell?.wraps = false
        }
        updateMediaMarqueeState()

        iconCenterYConstraint?.constant = !isExpanded ? actionOffset : 0
        textCenterYConstraint?.constant = !isExpanded ? actionOffset : 0
        iconCenterYConstraint?.isActive = !usesTopLayout
        textCenterYConstraint?.isActive = !usesTopLayout
        iconTopConstraint?.isActive = usesTopLayout
        textTopConstraint?.isActive = usesTopLayout
        textAboveActionsConstraint?.isActive = keepsActionRowVisible
        actionBelowTextConstraint?.isActive = keepsActionRowVisible
        actionContainerHeightConstraint?.constant = keepsActionRowVisible ? actionContentHeight : 0
        updateExpandButton()
        animateDisplayChanges(
            height:
            isExpanded
                ? Self.expandedHeight(
                    forTitle: fullTitle,
                    body: fullBody,
                    includesActions: shouldShowActionRow,
                    actionContentHeight: actionContentHeight,
                    titleFont: titleLabel.font ?? .systemFont(ofSize: 14, weight: .semibold),
                    bodyFont: bodyLabel.font ?? .systemFont(ofSize: 13)
                )
                : (isMediaNotification
                   ? Self.mediaCollapsedHeight
                   : (shouldShowActionRow ? Self.collapsedHeight + actionContentHeight + 2 : Self.collapsedHeight)),
            actionsVisible: shouldShowActionRow,
            animated: animated
        )
    }

    private func rebuildActionButtons() {
        actionContentStack.arrangedSubviews.forEach { view in
            actionContentStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        actionStack.arrangedSubviews.forEach { view in
            actionStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        actionStackWidthConstraint?.isActive = false
        actionStack.spacing = 10
        actionStack.distribution = .fill

        if detectedOTPCode != nil {
            let button = NotificationActionButton(title: "Copy code", target: self, action: #selector(performNotificationAction(_:)))
            button.identifier = NSUserInterfaceItemIdentifier(Self.copyOTPActionId)
            button.controlSize = .small
            button.font = .systemFont(ofSize: 11, weight: .semibold)
            button.isEnabled = true
            configureActionButton(button)
            button.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            button.widthAnchor.constraint(equalToConstant: Self.actionButtonWidth(for: button.title, font: button.font)).isActive = true
            button.heightAnchor.constraint(equalToConstant: 24).isActive = true
            actionStack.addArrangedSubview(button)
        }

        notificationActions.forEach { action in
            let button = NotificationActionButton(title: action.cleanTitle, target: self, action: #selector(performNotificationAction(_:)))
            button.identifier = NSUserInterfaceItemIdentifier(action.id)
            button.controlSize = .small
            button.font = .systemFont(ofSize: 11, weight: .semibold)
            button.isEnabled = true
            if isMediaNotification {
                configureMediaActionButton(button, title: action.cleanTitle)
            } else {
                configureActionButton(button)
            }
            button.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            if isMediaNotification {
                let buttonSize = Self.mediaActionButtonSize(for: action.cleanTitle)
                button.widthAnchor.constraint(equalToConstant: buttonSize).isActive = true
                button.heightAnchor.constraint(equalToConstant: buttonSize).isActive = true
            } else {
                button.widthAnchor.constraint(equalToConstant: Self.actionButtonWidth(for: action.cleanTitle, font: button.font)).isActive = true
                button.heightAnchor.constraint(equalToConstant: 24).isActive = true
            }
            actionStack.addArrangedSubview(button)
        }
        let showsButtons = detectedOTPCode != nil || !notificationActions.isEmpty
        if showsButtons {
            if isMediaNotification {
                actionStack.spacing = 0
                actionStack.distribution = .equalSpacing
                actionStackWidthConstraint?.isActive = true
            }
        }
        if isMediaNotification {
            if showsButtons {
                actionContentStack.addArrangedSubview(actionStack)
            }
            if shouldShowMediaTimeline {
                mediaTimelineView.isEnabled = mediaCanSeek
                mediaTimelineView.toolTip = mediaCanSeek ? "Seek Android media" : "Android media timeline"
                mediaTimelineView.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
                actionContentStack.addArrangedSubview(mediaTimelineView)
            }
        } else if showsButtons {
            actionContentStack.addArrangedSubview(actionStack)
        }
        actionStack.isHidden = true
        actionStack.alphaValue = 0
        actionContentStack.isHidden = true
        actionContentStack.alphaValue = 0
        replyComposerView.isHidden = true
        replyComposerView.alphaValue = 0
        actionContainerView.isHidden = true
        actionContainerView.alphaValue = 0
    }

    private func currentActionContentHeight() -> CGFloat {
        if activeReplyAction != nil {
            return Self.actionRowHeight
        }

        let showsButtons = detectedOTPCode != nil || !notificationActions.isEmpty
        let actionRowHeight = isMediaNotification ? Self.mediaActionRowHeight : Self.actionRowHeight
        switch (shouldShowMediaTimeline, showsButtons) {
        case (true, true):
            return Self.mediaTimelineRowHeight + Self.mediaTimelineSpacing + actionRowHeight
        case (true, false):
            return Self.mediaTimelineRowHeight
        case (false, true):
            return actionRowHeight
        case (false, false):
            return 0
        }
    }

    private var shouldShowMediaTimeline: Bool {
        guard isMediaNotification else { return false }
        return (mediaDurationMillis ?? 0) > 0 && mediaPositionMillis != nil
    }

    private func updateMediaTimelineSlider() {
        guard shouldShowMediaTimeline, let duration = mediaDurationMillis else { return }
        let position = currentMediaPositionMillis()
        isUpdatingMediaTimelineSlider = true
        mediaTimelineView.minimumValue = 0
        mediaTimelineView.maximumValue = Double(duration)
        mediaTimelineView.timelineValue = Double(position)
        mediaTimelineView.isEnabled = mediaCanSeek
        mediaTimelineView.elapsedText = Self.formattedMediaTime(position)
        mediaTimelineView.remainingText = "-\(Self.formattedMediaTime(max(duration - position, 0)))"
        isUpdatingMediaTimelineSlider = false
    }

    private func updateMediaMarqueeState() {
        let shouldScroll = isMediaNotification && mediaIsPlaying
        titleLabel.setMarqueeActive(shouldScroll)
        bodyLabel.setMarqueeActive(shouldScroll)
    }

    private func updateMediaTimelineTimer() {
        guard shouldShowMediaTimeline, mediaIsPlaying else {
            stopMediaTimelineTimer()
            return
        }

        guard mediaTimelineTimer == nil else { return }

        let timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            guard let self else { return }
            self.updateMediaTimelineSlider()
            if let duration = self.mediaDurationMillis,
               self.currentMediaPositionMillis() >= duration {
                self.stopMediaTimelineTimer()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        mediaTimelineTimer = timer
    }

    private func stopMediaTimelineTimer() {
        mediaTimelineTimer?.invalidate()
        mediaTimelineTimer = nil
    }

    private func currentMediaPositionMillis() -> Int64 {
        guard let basePosition = mediaPositionMillis else { return 0 }
        let duration = mediaDurationMillis ?? basePosition
        guard mediaIsPlaying, let updatedAt = mediaPositionUpdatedAt else {
            return min(max(basePosition, 0), duration)
        }

        let elapsedMillis = Int64(Date().timeIntervalSince(updatedAt) * 1000)
        let compensatedPosition = basePosition + elapsedMillis + Self.activeMediaDisplayOffsetMillis
        return min(max(compensatedPosition, 0), duration)
    }

    @objc private func seekMediaTimeline(_ sender: MediaTimelineControl) {
        guard !isUpdatingMediaTimelineSlider, mediaCanSeek else { return }
        guard let notificationKey, !notificationKey.isEmpty else { return }

        let targetMillis = Int64(sender.timelineValue.rounded())
        mediaPositionMillis = targetMillis
        mediaPositionUpdatedAt = Date()
        onAction(notificationKey, Self.mediaSeekActionId, String(targetMillis)) { [weak self] success, _ in
            DispatchQueue.main.async {
                if !success {
                    self?.updateMediaTimelineSlider()
                }
            }
        }
    }

    @objc private func performNotificationAction(_ sender: NSButton) {
        guard let actionId = sender.identifier?.rawValue else {
            return
        }

        if actionId == Self.copyOTPActionId {
            copyDetectedOTPCode(from: sender)
            return
        }

        guard
            let notificationKey,
            !notificationKey.isEmpty,
            let action = notificationActions.first(where: { $0.id == actionId })
        else {
            return
        }

        if action.requiresTextInput == true {
            showInlineReplyComposer(for: action)
            return
        }

        let previousMediaIsPlaying = mediaIsPlaying
        let updatedPlaybackOptimistically = applyOptimisticMediaPlaybackState(for: action.cleanTitle)
        if !isMediaNotification {
            sender.isEnabled = false
        }
        onAction(notificationKey, action.id, nil) { [weak self, weak sender] success, _ in
            DispatchQueue.main.async {
                sender?.isEnabled = true
                if !success, updatedPlaybackOptimistically {
                    self?.mediaIsPlaying = previousMediaIsPlaying
                    self?.updateMediaMarqueeState()
                    self?.updateMediaTimelineTimer()
                }
                if success && self?.isMediaNotification != true {
                    self?.close()
                }
            }
        }
    }

    private func applyOptimisticMediaPlaybackState(for title: String) -> Bool {
        guard isMediaNotification, Self.isPrimaryMediaAction(title) else { return false }

        let normalizedTitle = Self.normalizedMediaActionTitle(title)
        if normalizedTitle.contains("pause") || normalizedTitle.contains("stop") {
            mediaIsPlaying = false
        } else if normalizedTitle.contains("play") || normalizedTitle.contains("resume") {
            mediaIsPlaying = true
            mediaPositionUpdatedAt = Date()
        } else {
            return false
        }

        updateMediaMarqueeState()
        updateMediaTimelineTimer()
        return true
    }

    private func copyDetectedOTPCode(from sender: NSButton) {
        guard let detectedOTPCode, !detectedOTPCode.isEmpty else { return }

        sender.isEnabled = false
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(detectedOTPCode, forType: .string)
        dismissFromAndroid()
    }

    private func showInlineReplyComposer(for action: AndroidMirroredNotificationAction) {
        activeReplyAction = action
        autoCloseTimer?.invalidate()
        autoCloseTimer = nil
        replyTextField.stringValue = ""
        updateReplyCursorPosition()
        let placeholder = action.inputLabel?.isEmpty == false ? action.inputLabel! : "Reply"
        replyTextField.placeholderString = placeholder
        replyTextField.placeholderAttributedString = Self.placeholderString(placeholder)
        replySendButton.isEnabled = true
        replyBackButton.isEnabled = true
        replyTextField.isEnabled = true
        applyDisplayState(animated: true)
        panel.orderFrontRegardless()
        startInlineReplyKeyCapture()
        startReplyCursorBlink()
    }

    @objc private func cancelInlineReply() {
        activeReplyAction = nil
        stopInlineReplyKeyCapture()
        stopReplyCursorBlink()
        replyTextField.stringValue = ""
        replyBackButton.isEnabled = true
        replySendButton.isEnabled = true
        replyTextField.isEnabled = true
        applyDisplayState(animated: true)
        restartAutoCloseTimer(after: Self.unhoverVisibleDuration)
    }

    @objc private func sendInlineReply() {
        guard
            let notificationKey,
            !notificationKey.isEmpty,
            let action = activeReplyAction
        else {
            return
        }

        let replyText = replyTextField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !replyText.isEmpty else { return }

        activeReplyAction = nil
        stopInlineReplyKeyCapture()
        stopReplyCursorBlink()
        replyTextField.stringValue = ""
        replyBackButton.isEnabled = true
        replySendButton.isEnabled = true
        replyTextField.isEnabled = true
        applyDisplayState(animated: true)
        restartAutoCloseTimer(after: Self.unhoverVisibleDuration)
        onAction(notificationKey, action.id, replyText) { [weak self] success, _ in
            DispatchQueue.main.async {
                guard let self else { return }
                if !success {
                    self.applyDisplayState(animated: true)
                }
            }
        }
    }

    private func startInlineReplyKeyCapture() {
        stopInlineReplyKeyCapture()
        NSApp.activate(ignoringOtherApps: true)
        replyKeyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self, self.activeReplyAction != nil else {
                return event
            }
            return self.handleInlineReplyKey(event)
        }
    }

    private func stopInlineReplyKeyCapture() {
        if let replyKeyMonitor {
            NSEvent.removeMonitor(replyKeyMonitor)
            self.replyKeyMonitor = nil
        }
    }

    private func startReplyCursorBlink() {
        stopReplyCursorBlink()
        replyCursorView.isHidden = false
        replyCursorView.alphaValue = 1
        updateReplyCursorPosition()
        replyCursorBlinkTimer = Timer.scheduledTimer(withTimeInterval: 0.55, repeats: true) { [weak self] _ in
            guard let self, self.activeReplyAction != nil else { return }
            self.replyCursorView.alphaValue = self.replyCursorView.alphaValue == 0 ? 1 : 0
        }
    }

    private func stopReplyCursorBlink() {
        replyCursorBlinkTimer?.invalidate()
        replyCursorBlinkTimer = nil
        replyCursorView.isHidden = true
        replyCursorView.alphaValue = 0
    }

    private func updateReplyCursorPosition() {
        let font = replyTextField.font ?? .systemFont(ofSize: 13, weight: .medium)
        let typedWidth = Self.textWidth(replyTextField.stringValue, font: font)
        let maxCursorX = max(12, replyFieldContainer.bounds.width - 14)
        replyCursorLeadingConstraint?.constant = min(12 + ceil(typedWidth), maxCursorX)
        replyFieldContainer.layoutSubtreeIfNeeded()
    }

    private func handleInlineReplyKey(_ event: NSEvent) -> NSEvent? {
        if event.modifierFlags.intersection([.command, .control, .option]).isEmpty == false {
            return event
        }

        switch event.keyCode {
        case 36, 76:
            sendInlineReply()
            return nil
        case 51:
            let currentText = replyTextField.stringValue
            if !currentText.isEmpty {
                replyTextField.stringValue = String(currentText.dropLast())
                updateReplyCursorPosition()
                replyCursorView.alphaValue = 1
            }
            return nil
        case 53:
            cancelInlineReply()
            return nil
        default:
            guard let characters = event.characters, !characters.isEmpty else {
                return nil
            }
            replyTextField.stringValue += characters.filter { !$0.isNewline }
            updateReplyCursorPosition()
            replyCursorView.alphaValue = 1
            return nil
        }
    }

    private func updateExpandButton() {
        expandButton.isHidden = isMediaNotification || !(hasLongContent && (isHovering || isExpanded))
        if let symbolName = isExpanded ? "chevron.down" : "chevron.right",
           let image = Self.chevronImage(named: symbolName, accessibilityDescription: isExpanded ? "Collapse" : "Expand") {
            expandButton.image = image
            expandButton.title = ""
        } else {
            expandButton.title = isExpanded ? "v" : ">"
        }
    }

    private func animateDisplayChanges(height: CGFloat, actionsVisible: Bool, animated: Bool) {
        let frame = panel.frame
        let topY = targetTopY ?? frame.maxY
        targetTopY = topY
        let shouldDeferResizeUntilFadeOut = animated && !actionsVisible && !actionContainerView.isHidden
        let showsReplyComposer = activeReplyAction != nil
        let newFrame = NSRect(
            x: frame.minX,
            y: topY - (shouldDeferResizeUntilFadeOut ? frame.height : height),
            width: frame.width,
            height: shouldDeferResizeUntilFadeOut ? frame.height : height
        )
        let finalFrame = NSRect(
            x: frame.minX,
            y: topY - height,
            width: frame.width,
            height: height
        )

        if actionsVisible {
            actionContainerView.isHidden = false
            actionContentStack.isHidden = showsReplyComposer
            actionStack.isHidden = showsReplyComposer
            replyComposerView.isHidden = false
            if !animated && actionContainerView.alphaValue == 0 {
                actionContainerView.alphaValue = 1
            }
            if animated && actionContainerView.alphaValue == 0 {
                actionContainerView.alphaValue = 0
                actionContentStack.alphaValue = showsReplyComposer ? 0 : 1
                actionStack.alphaValue = showsReplyComposer ? 0 : 1
                replyComposerView.alphaValue = showsReplyComposer ? 1 : 0
            }
        }

        let changes = {
            self.actionContainerView.alphaValue = actionsVisible ? 1 : 0
            self.actionContentStack.alphaValue = actionsVisible && !showsReplyComposer ? 1 : 0
            self.actionStack.alphaValue = actionsVisible && !showsReplyComposer ? 1 : 0
            self.replyComposerView.alphaValue = actionsVisible && showsReplyComposer ? 1 : 0
            self.panel.setFrame(newFrame, display: true, animate: false)
            self.panel.contentView?.layoutSubtreeIfNeeded()
            if self.panel.isVisible {
                self.onLayoutChange()
            }
        }

        guard animated else {
            changes()
            actionContentStack.isHidden = !actionsVisible || showsReplyComposer
            actionStack.isHidden = !actionsVisible || showsReplyComposer
            replyComposerView.isHidden = !actionsVisible || !showsReplyComposer
            actionContainerView.isHidden = !actionsVisible
            return
        }

        NSAnimationContext.runAnimationGroup { context in
            context.duration = 0
            context.allowsImplicitAnimation = false
            self.panel.setFrame(newFrame, display: true, animate: false)
            self.trackingContentView?.layoutSubtreeIfNeeded()
            self.panel.contentView?.layoutSubtreeIfNeeded()
            if self.panel.isVisible {
                self.onLayoutChange()
            }
        }

        NSAnimationContext.runAnimationGroup { context in
            context.duration = Self.hoverAnimationDuration
            context.allowsImplicitAnimation = true
            context.timingFunction = CAMediaTimingFunction(name: actionsVisible ? .easeOut : .easeIn)
            self.actionContainerView.animator().alphaValue = actionsVisible ? 1 : 0
            self.actionContentStack.animator().alphaValue = actionsVisible && !showsReplyComposer ? 1 : 0
            self.actionStack.animator().alphaValue = actionsVisible && !showsReplyComposer ? 1 : 0
            self.replyComposerView.animator().alphaValue = actionsVisible && showsReplyComposer ? 1 : 0
        } completionHandler: { [weak self] in
            guard let self else { return }
            if !actionsVisible {
                self.actionContentStack.isHidden = true
                self.actionStack.isHidden = true
                self.replyComposerView.isHidden = true
                self.actionContainerView.isHidden = true
                self.textAboveActionsConstraint?.isActive = false
                self.actionBelowTextConstraint?.isActive = false
                self.actionContainerHeightConstraint?.constant = 0
                self.panel.setFrame(finalFrame, display: true, animate: false)
                self.trackingContentView?.layoutSubtreeIfNeeded()
                self.panel.contentView?.layoutSubtreeIfNeeded()
                if self.panel.isVisible {
                    self.onLayoutChange()
                }
            } else {
                self.actionContentStack.isHidden = showsReplyComposer
                self.actionStack.isHidden = showsReplyComposer
                self.replyComposerView.isHidden = !showsReplyComposer
            }
        }
    }

    private func configureGlassButton(_ button: NSButton, fallbackCornerRadius: CGFloat) {
        button.appearance = NSApp.effectiveAppearance
        button.contentTintColor = .labelColor
        button.focusRingType = .none
        if #available(macOS 26.0, *) {
            button.isBordered = true
            button.bezelStyle = .glass
        } else {
            button.isBordered = false
            button.wantsLayer = true
            button.layer?.backgroundColor = NSColor.white.withAlphaComponent(0.10).cgColor
            button.layer?.cornerRadius = fallbackCornerRadius
        }
    }

    private func configureActionButton(_ button: NSButton) {
        (button as? NotificationActionButton)?.pressAnimationScale = 1
        button.appearance = NSAppearance(named: .darkAqua)
        button.isBordered = false
        button.wantsLayer = true
        button.layer?.backgroundColor = NSColor.white.withAlphaComponent(0.08).cgColor
        button.layer?.cornerRadius = 12
        button.layer?.masksToBounds = true
        button.focusRingType = .none
        button.contentTintColor = .white
        button.alignment = .center
        button.cell?.alignment = .center
        applyReadableButtonTitle(button.title, to: button)
    }

    private func configureMediaActionButton(_ button: NSButton, title: String) {
        (button as? NotificationActionButton)?.pressAnimationScale = Self.isPrimaryMediaAction(title) ? 0.82 : 1
        button.appearance = NSAppearance(named: .darkAqua)
        button.isBordered = false
        button.wantsLayer = true
        button.layer?.backgroundColor = NSColor.clear.cgColor
        button.layer?.masksToBounds = false
        button.focusRingType = .none
        button.contentTintColor = .white
        button.imagePosition = .imageOnly
        button.title = ""
        button.attributedTitle = NSAttributedString(string: "")
        button.attributedAlternateTitle = NSAttributedString(string: "")
        button.toolTip = title

        if let symbolName = Self.mediaActionSymbolName(for: title),
           let image = NSImage(systemSymbolName: symbolName, accessibilityDescription: title) {
            button.image = image.withSymbolConfiguration(.init(pointSize: Self.mediaActionSymbolPointSize(for: title), weight: .semibold))
        } else if let image = NSImage(systemSymbolName: "ellipsis", accessibilityDescription: title) {
            button.image = image.withSymbolConfiguration(.init(pointSize: 15, weight: .semibold))
        }
    }

    private func applyReadableButtonTitle(_ title: String, to button: NSButton) {
        let paragraphStyle = NSMutableParagraphStyle()
        paragraphStyle.alignment = .center
        paragraphStyle.lineBreakMode = .byTruncatingTail
        button.cell?.lineBreakMode = .byTruncatingTail
        button.attributedTitle = NSAttributedString(
            string: title,
            attributes: [
                .font: button.font ?? .systemFont(ofSize: 12, weight: .medium),
                .foregroundColor: NSColor.white,
                .paragraphStyle: paragraphStyle
            ]
        )
        button.attributedAlternateTitle = button.attributedTitle
    }

    private static func actionButtonWidth(for title: String, font: NSFont?) -> CGFloat {
        let font = font ?? .systemFont(ofSize: 11, weight: .semibold)
        let titleWidth = textWidth(title, font: font)
        return min(max(ceil(titleWidth) + 26, 54), 118)
    }

    private static func mediaActionButtonSize(for title: String) -> CGFloat {
        isPrimaryMediaAction(title) ? 38 : 30
    }

    private static func mediaActionSymbolPointSize(for title: String) -> CGFloat {
        isPrimaryMediaAction(title) ? 25 : 20
    }

    private static func isPrimaryMediaAction(_ title: String) -> Bool {
        let normalizedTitle = normalizedMediaActionTitle(title)
        return normalizedTitle.contains("play")
            || normalizedTitle.contains("pause")
            || normalizedTitle.contains("resume")
    }

    private static func mediaActionSymbolName(for title: String) -> String? {
        let normalizedTitle = normalizedMediaActionTitle(title)

        if normalizedTitle.contains("previous") || normalizedTitle.contains("rewind") {
            return "backward.end.fill"
        }
        if normalizedTitle.contains("next") || normalizedTitle.contains("forward") {
            return "forward.end.fill"
        }
        if normalizedTitle.contains("pause") {
            return "pause.fill"
        }
        if normalizedTitle.contains("play") || normalizedTitle.contains("resume") {
            return "play.fill"
        }
        if normalizedTitle.contains("stop") {
            return "stop.fill"
        }
        if normalizedTitle.contains("shuffle") {
            return "shuffle"
        }
        if normalizedTitle.contains("repeat") {
            return "repeat"
        }
        if normalizedTitle.contains("dislike") {
            return "hand.thumbsdown.fill"
        }
        if normalizedTitle.contains("like") || normalizedTitle.contains("favorite") || normalizedTitle.contains("favourite") {
            return "star.fill"
        }
        if normalizedTitle.contains("mute") {
            return "speaker.slash.fill"
        }
        if normalizedTitle.contains("copy") {
            return "doc.on.doc.fill"
        }

        return nil
    }

    private static func normalizedMediaActionTitle(_ title: String) -> String {
        title
            .lowercased()
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    private static func chevronImage(named symbolName: String, accessibilityDescription: String) -> NSImage? {
        guard let image = NSImage(systemSymbolName: symbolName, accessibilityDescription: accessibilityDescription) else {
            return nil
        }
        if #available(macOS 12.0, *) {
            return image.withSymbolConfiguration(.init(pointSize: 10, weight: .semibold))
        }
        return image
    }

    private static func fittedSingleLine(_ text: String, font: NSFont) -> String {
        let singleLine = singleLine(text)
        guard textWidth(singleLine, font: font) > textColumnWidth else {
            return singleLine
        }

        let ellipsis = "..."
        var lowerBound = 0
        var upperBound = singleLine.count
        let characters = Array(singleLine)

        while lowerBound < upperBound {
            let midpoint = (lowerBound + upperBound + 1) / 2
            let candidate = String(characters.prefix(midpoint)) + ellipsis
            if textWidth(candidate, font: font) <= textColumnWidth {
                lowerBound = midpoint
            } else {
                upperBound = midpoint - 1
            }
        }

        return String(characters.prefix(lowerBound)).trimmingCharacters(in: .whitespaces) + ellipsis
    }

    private static func textWidth(_ text: String, font: NSFont) -> CGFloat {
        (text as NSString).size(withAttributes: [.font: font]).width
    }

    private static func formattedMediaTime(_ milliseconds: Int64) -> String {
        let totalSeconds = max(0, milliseconds / 1000)
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60

        if hours > 0 {
            return String(format: "%lld:%02lld:%02lld", hours, minutes, seconds)
        }

        return String(format: "%lld:%02lld", minutes, seconds)
    }

    private static func placeholderString(_ text: String) -> NSAttributedString {
        NSAttributedString(
            string: text,
            attributes: [
                .font: NSFont.systemFont(ofSize: 13, weight: .medium),
                .foregroundColor: NSColor.secondaryLabelColor
            ]
        )
    }

    private static func requiresExpansion(
        title: String,
        body: String,
        titleFont: NSFont,
        bodyFont: NSFont
    ) -> Bool {
        textWidth(title, font: titleFont) > textColumnWidth || textWidth(body, font: bodyFont) > textColumnWidth
    }

    private static func expandedHeight(
        forTitle title: String,
        body: String,
        includesActions: Bool,
        actionContentHeight: CGFloat,
        titleFont: NSFont,
        bodyFont: NSFont
    ) -> CGFloat {
        let titleRect = (singleLine(title) as NSString).boundingRect(
            with: NSSize(width: textColumnWidth, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [.font: titleFont]
        )
        let bodyRect = (singleLine(body) as NSString).boundingRect(
            with: NSSize(width: textColumnWidth, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [.font: bodyFont]
        )
        let bodyLineHeight = ceil(bodyFont.pointSize + 3)
        let titleLineHeight = ceil(titleFont.pointSize + 4)
        let estimatedBodyLines = max(1, ceil(ceil(bodyRect.height) / max(bodyLineHeight, 1)))
        let visibleBodyLines = min(estimatedBodyLines, CGFloat(expandedBodyLineLimit))
        let cappedBodyHeight = visibleBodyLines * bodyLineHeight
        let topPadding: CGFloat = 18
        let bottomPadding: CGFloat = includesActions ? 6 : 0
        let titleBodySpacing: CGFloat = 1
        let actionHeight: CGFloat = includesActions ? 8 + actionContentHeight : 0
        let contentHeight = topPadding
            + max(ceil(titleRect.height), titleLineHeight)
            + titleBodySpacing
            + cappedBodyHeight
            + actionHeight
            + bottomPadding
        return min(expandedMaxHeight, max(expandedMinHeight, ceil(contentHeight)))
    }

    private static func detectOTPCode(in textParts: [String?]) -> String? {
        let message = textParts
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        guard !message.isEmpty else { return nil }

        let lowercasedMessage = message.lowercased()
        let otpKeywords = [
            "otp",
            "one time",
            "one-time",
            "verification",
            "verify",
            "code",
            "passcode",
            "security code",
            "login code",
            "authentication"
        ]
        guard otpKeywords.contains(where: { lowercasedMessage.contains($0) }) else {
            return nil
        }

        let range = NSRange(message.startIndex..<message.endIndex, in: message)
        guard
            let regex = try? NSRegularExpression(pattern: #"(?<!\d)(\d(?:[ -]?\d){3,7})(?!\d)"#)
        else {
            return nil
        }

        for match in regex.matches(in: message, range: range) {
            guard let matchRange = Range(match.range(at: 1), in: message) else { continue }
            let candidate = message[matchRange].filter(\.isNumber)
            if candidate.count >= 4 && candidate.count <= 8 {
                return String(candidate)
            }
        }

        return nil
    }

    private static func singleLine(_ text: String) -> String {
        text
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    fileprivate static func singleLineForDisplay(_ text: String) -> String {
        singleLine(text)
    }

    @objc private func dismissFromAndroid() {
        if let notificationKey, !notificationKey.isEmpty {
            onDismiss(notificationKey)
        }
        close()
    }
}

private class HoverTrackingView: NSView {
    var onHoverChanged: ((Bool) -> Void)?
    var onSwipeRight: (() -> Void)?
    var onSwipeUp: (() -> Void)?
    var allowsScrollWheelSwipeUp = true
    private var trackingAreaReference: NSTrackingArea?
    private var horizontalSwipeAmount: CGFloat = 0
    private var verticalSwipeAmount: CGFloat = 0
    private var didTriggerSwipeDismiss = false
    private var didTriggerSwipeUp = false
    private static let swipeDismissThreshold: CGFloat = 80
    private static let swipeUpThreshold: CGFloat = 70

    override func updateTrackingAreas() {
        if let trackingAreaReference {
            removeTrackingArea(trackingAreaReference)
        }

        let trackingArea = NSTrackingArea(
            rect: bounds,
            options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(trackingArea)
        trackingAreaReference = trackingArea
        super.updateTrackingAreas()
    }

    override func mouseEntered(with event: NSEvent) {
        onHoverChanged?(true)
    }

    override func mouseExited(with event: NSEvent) {
        onHoverChanged?(false)
    }

    override func swipe(with event: NSEvent) {
        if event.deltaX < 0 {
            triggerSwipeDismiss()
            return
        }
        if event.deltaY > 0 {
            triggerSwipeUp()
            return
        }
        super.swipe(with: event)
    }

    override func scrollWheel(with event: NSEvent) {
        let phase = event.phase
        if phase.contains(.began) || phase.contains(.mayBegin) {
            resetSwipeTracking()
        }

        let horizontal = normalizedFingerHorizontalDelta(from: event)
        let vertical = normalizedFingerVerticalDelta(from: event)
        let isHorizontalSwipe = abs(horizontal) > abs(vertical) * 1.4 && abs(horizontal) > 0.4
        let isVerticalSwipe = abs(vertical) > abs(horizontal) * 1.4 && abs(vertical) > 0.4

        if isHorizontalSwipe {
            horizontalSwipeAmount += horizontal
            if horizontalSwipeAmount < -Self.swipeDismissThreshold {
                triggerSwipeDismiss()
                return
            }
        } else if allowsScrollWheelSwipeUp && isVerticalSwipe && onSwipeUp != nil {
            verticalSwipeAmount += vertical
            if verticalSwipeAmount > Self.swipeUpThreshold {
                triggerSwipeUp()
                return
            }
        } else if abs(vertical) > abs(horizontal) {
            resetSwipeTracking()
        }

        if phase.contains(.ended)
            || phase.contains(.cancelled)
            || event.momentumPhase.contains(.ended)
            || event.momentumPhase.contains(.cancelled) {
            resetSwipeTracking()
        }

        super.scrollWheel(with: event)
    }

    private func normalizedFingerHorizontalDelta(from event: NSEvent) -> CGFloat {
        if event.isDirectionInvertedFromDevice {
            return -event.scrollingDeltaX
        }
        return event.scrollingDeltaX
    }

    private func normalizedFingerVerticalDelta(from event: NSEvent) -> CGFloat {
        if event.isDirectionInvertedFromDevice {
            return -event.scrollingDeltaY
        }
        return event.scrollingDeltaY
    }

    private func triggerSwipeDismiss() {
        guard !didTriggerSwipeDismiss else { return }
        didTriggerSwipeDismiss = true
        onSwipeRight?()
    }

    private func triggerSwipeUp() {
        guard !didTriggerSwipeUp else { return }
        didTriggerSwipeUp = true
        onSwipeUp?()
    }

    private func resetSwipeTracking() {
        horizontalSwipeAmount = 0
        verticalSwipeAmount = 0
        didTriggerSwipeDismiss = false
        didTriggerSwipeUp = false
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

private final class MarqueeTextField: NSTextField {
    private var marqueeTimer: Timer?
    private var marqueeOffset: CGFloat = 0
    private var marqueeActive = false
    private let marqueeGap: CGFloat = 36

    func setMarqueeActive(_ isActive: Bool) {
        marqueeActive = isActive
        if isActive {
            startMarqueeIfNeeded()
        } else {
            stopMarquee()
        }
        needsDisplay = true
    }

    override var stringValue: String {
        didSet {
            marqueeOffset = 0
            if marqueeActive {
                startMarqueeIfNeeded()
            }
            needsDisplay = true
        }
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if window == nil {
            stopMarquee()
        } else if marqueeActive {
            startMarqueeIfNeeded()
        }
    }

    override func layout() {
        super.layout()
        if marqueeActive {
            startMarqueeIfNeeded()
        }
    }

    override func draw(_ dirtyRect: NSRect) {
        let attributes = textAttributes()
        let text = stringValue as NSString
        let textWidth = text.size(withAttributes: attributes).width
        guard marqueeActive, textWidth > bounds.width, bounds.width > 0 else {
            super.draw(dirtyRect)
            return
        }

        NSGraphicsContext.saveGraphicsState()
        bounds.clip()
        let y = max(0, (bounds.height - ceil(font?.pointSize ?? 13)) / 2 - 1)
        text.draw(at: NSPoint(x: -marqueeOffset, y: y), withAttributes: attributes)
        text.draw(at: NSPoint(x: textWidth + marqueeGap - marqueeOffset, y: y), withAttributes: attributes)
        NSGraphicsContext.restoreGraphicsState()
    }

    private func startMarqueeIfNeeded() {
        guard window != nil else { return }
        guard measuredTextWidth() > bounds.width else {
            stopMarquee(resetOffset: true)
            return
        }
        guard marqueeTimer == nil else { return }

        marqueeTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / 30.0, repeats: true) { [weak self] _ in
            guard let self else { return }
            let wrapWidth = self.measuredTextWidth() + self.marqueeGap
            self.marqueeOffset = wrapWidth > 0 ? (self.marqueeOffset + 0.7).truncatingRemainder(dividingBy: wrapWidth) : 0
            self.needsDisplay = true
        }
        if let marqueeTimer {
            RunLoop.main.add(marqueeTimer, forMode: .common)
        }
    }

    private func stopMarquee(resetOffset: Bool = true) {
        marqueeTimer?.invalidate()
        marqueeTimer = nil
        if resetOffset {
            marqueeOffset = 0
        }
    }

    private func measuredTextWidth() -> CGFloat {
        (stringValue as NSString).size(withAttributes: textAttributes()).width
    }

    private func textAttributes() -> [NSAttributedString.Key: Any] {
        [
            .font: font ?? NSFont.systemFont(ofSize: 13),
            .foregroundColor: textColor ?? NSColor.labelColor
        ]
    }
}

private final class MediaTimelineControl: NSControl {
    var minimumValue: Double = 0 { didSet { needsDisplay = true } }
    var maximumValue: Double = 1 { didSet { needsDisplay = true } }
    var timelineValue: Double = 0 { didSet { needsDisplay = true } }
    var elapsedText = "0:00" { didSet { needsDisplay = true } }
    var remainingText = "-0:00" { didSet { needsDisplay = true } }

    private var trackingAreaReference: NSTrackingArea?
    private var isHovering = false { didSet { needsDisplay = true } }
    private var isDragging = false { didSet { needsDisplay = true } }

    override var isFlipped: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func updateTrackingAreas() {
        if let trackingAreaReference {
            removeTrackingArea(trackingAreaReference)
        }
        let trackingArea = NSTrackingArea(
            rect: bounds,
            options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect],
            owner: self,
            userInfo: nil
        )
        addTrackingArea(trackingArea)
        trackingAreaReference = trackingArea
        super.updateTrackingAreas()
    }

    override func mouseEntered(with event: NSEvent) {
        isHovering = true
    }

    override func mouseExited(with event: NSEvent) {
        isHovering = false
    }

    override func mouseDown(with event: NSEvent) {
        guard isEnabled else { return }
        isDragging = true
        updateValue(from: event)
    }

    override func mouseDragged(with event: NSEvent) {
        guard isEnabled else { return }
        updateValue(from: event)
    }

    override func mouseUp(with event: NSEvent) {
        guard isEnabled else { return }
        updateValue(from: event)
        isDragging = false
        sendAction(action, to: target)
    }

    override func draw(_ dirtyRect: NSRect) {
        super.draw(dirtyRect)

        let barHeight: CGFloat = 5
        let barY: CGFloat = 6
        let barRect = NSRect(x: 0, y: barY, width: bounds.width, height: barHeight)
        let progress = CGFloat((timelineValue - minimumValue) / max(maximumValue - minimumValue, 1))
            .clamped(to: 0...1)

        NSColor.white.withAlphaComponent(0.18).setFill()
        NSBezierPath(roundedRect: barRect, xRadius: barHeight / 2, yRadius: barHeight / 2).fill()

        let progressRect = NSRect(x: 0, y: barY, width: bounds.width * progress, height: barHeight)
        NSColor.controlAccentColor.withAlphaComponent(0.85).setFill()
        NSBezierPath(roundedRect: progressRect, xRadius: barHeight / 2, yRadius: barHeight / 2).fill()

        if isHovering || isDragging {
            let knobRadius: CGFloat = isDragging ? 6 : 5
            let knobCenter = NSPoint(x: bounds.width * progress, y: barY + barHeight / 2)
            NSColor.white.withAlphaComponent(0.94).setFill()
            NSBezierPath(ovalIn: NSRect(
                x: knobCenter.x - knobRadius,
                y: knobCenter.y - knobRadius,
                width: knobRadius * 2,
                height: knobRadius * 2
            )).fill()
        }

        let labelFont = NSFont.monospacedDigitSystemFont(ofSize: 11, weight: .medium)
        let labelColor = NSColor.secondaryLabelColor
        let attributes: [NSAttributedString.Key: Any] = [
            .font: labelFont,
            .foregroundColor: labelColor
        ]
        let labelY: CGFloat = 18
        (elapsedText as NSString).draw(at: NSPoint(x: 0, y: labelY), withAttributes: attributes)
        let remainingSize = (remainingText as NSString).size(withAttributes: attributes)
        (remainingText as NSString).draw(
            at: NSPoint(x: max(0, bounds.width - remainingSize.width), y: labelY),
            withAttributes: attributes
        )
    }

    private func updateValue(from event: NSEvent) {
        let location = convert(event.locationInWindow, from: nil)
        let progress = Double((location.x / max(bounds.width, 1)).clamped(to: 0...1))
        timelineValue = minimumValue + ((maximumValue - minimumValue) * progress)
    }
}

private final class NotificationActionButton: NSButton {
    var pressAnimationScale: CGFloat = 1

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override var canBecomeKeyView: Bool { false }

    override func mouseDown(with event: NSEvent) {
        guard pressAnimationScale < 1 else {
            super.mouseDown(with: event)
            return
        }

        animatePressScale(pressAnimationScale, duration: 0.08)
        super.mouseDown(with: event)
        animatePressScale(1, duration: 0.16)
    }

    private func animatePressScale(_ scale: CGFloat, duration: TimeInterval) {
        wantsLayer = true
        NSAnimationContext.runAnimationGroup { context in
            context.duration = duration
            context.timingFunction = CAMediaTimingFunction(name: scale < 1 ? .easeOut : .easeInEaseOut)
            animator().layer?.setAffineTransform(CGAffineTransform(scaleX: scale, y: scale))
        }
    }
}

private final class InlineReplyTextField: NSTextField {
    override var acceptsFirstResponder: Bool { false }
}

private final class NotificationInteractionPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
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
    private let buttonSpacer = NSView()
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
            contentRect: NSRect(x: 0, y: 0, width: 336, height: 138),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.isMovableByWindowBackground = true
        panel.appearance = NSAppearance(named: .darkAqua)

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

        contentView.wantsLayer = true
        contentView.layer?.backgroundColor = NSColor(calibratedWhite: 0.07, alpha: 0.96).cgColor
        contentView.layer?.cornerRadius = 22
        contentView.layer?.masksToBounds = true

        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false

        titleLabel.font = .systemFont(ofSize: 16, weight: .semibold)
        titleLabel.textColor = .white
        titleLabel.lineBreakMode = .byTruncatingTail
        bodyLabel.font = .systemFont(ofSize: 13)
        bodyLabel.textColor = NSColor.white.withAlphaComponent(0.74)
        bodyLabel.lineBreakMode = .byTruncatingTail
        statusLabel.font = .systemFont(ofSize: 11)
        statusLabel.textColor = NSColor.white.withAlphaComponent(0.64)
        buttonSpacer.translatesAutoresizingMaskIntoConstraints = false

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

        [titleLabel, bodyLabel, statusLabel, buttonSpacer, buttonStack].forEach {
            stack.addArrangedSubview($0)
        }

        contentView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 18),
            stack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -18),
            stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 14),
            stack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -14),
            titleLabel.widthAnchor.constraint(equalTo: stack.widthAnchor),
            bodyLabel.widthAnchor.constraint(equalTo: stack.widthAnchor),
            buttonSpacer.heightAnchor.constraint(equalToConstant: 6)
        ])
    }

    private func configureButton(_ button: NSButton, action: Selector) {
        button.target = self
        button.action = action
        button.bezelStyle = .rounded
        button.controlSize = .small
        button.font = .systemFont(ofSize: 13, weight: .medium)
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
